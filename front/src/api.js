/**
 * 接口层：统一 Result 解包 + SSE 流式读取。
 *
 * 每个请求都会带上 `X-Client-Settings`（浏览器里那套客户端配置）。
 * 没配就不带，后端自动用 .env 那份 —— 所以这里不需要判断"用哪套"。
 *
 * 服务端信封：{ success, data, errMsg, code }（成功码 0，兼容 ai-runtime 的 200）。
 * 业务失败时 HTTP 仍是 200，所以不能只看 response.ok。
 *
 * 鉴权：每个请求自动带 `Authorization: Bearer <token>`（见 auth.js）。
 * 服务端回 4401（未登录/过期）时，这里统一清登录态并广播，App 会切回登录页——
 * 页面代码不用各自处理"登录过期"。
 */
import { authHeaders, notifyUnauthorized } from './auth';
import { clientHeaders } from './clientSettings';

export class ApiError extends Error {
  constructor(code, message, extra = {}) {
    super(message || '请求失败');
    this.name = 'ApiError';
    this.code = Number(code) || 5000;
    this.service = extra.service || null;
  }
}

const isOk = (json) => json && (json.success === true || json.code === 0 || json.code === 200);

/** 登录过期：清登录态 + 广播，然后抛出（调用方可以 catch 但不必处理跳转）。 */
function expired(message) {
  notifyUnauthorized(message);
  return new ApiError(4401, message || '登录已过期，请重新登录');
}

async function request(path, body, { method = 'POST' } = {}) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: method === 'GET'
        ? { ...authHeaders(), ...clientHeaders() }
        : { 'Content-Type': 'application/json', ...authHeaders(), ...clientHeaders() },
      body: method === 'GET' ? undefined : JSON.stringify(body ?? {}),
    });
  } catch {
    throw new ApiError(5000, '连不上本地服务，确认 server 还在运行');
  }

  // 鉴权失败是 HTTP 401（本项目唯一的例外），body 仍是信封
  if (res.status === 401) throw expired('登录已过期，请重新登录');

  let json = null;
  try {
    json = await res.json();
  } catch {
    throw new ApiError(5000, `服务返回异常（HTTP ${res.status}）`);
  }
  if (Number(json?.code) === 4401) throw expired(json.errMsg);
  if (!isOk(json)) throw new ApiError(json.code, json.errMsg || '请求失败');
  return json.data;
}

export const post = (path, body = {}) => request(path, body);
export const get = (path) => request(path, null, { method: 'GET' });

/** POST 并消费 SSE 流；每一帧回调 onEvent(event, data)。 */
export async function postStream(path, body, onEvent, signal) {
  let res;
  try {
    res = await fetch(path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...authHeaders(),
        ...clientHeaders(),
      },
      body: JSON.stringify(body ?? {}),
      signal,
    });
  } catch (err) {
    if (err.name === 'AbortError') return;
    throw new ApiError(5000, '连不上本地服务');
  }
  if (res.status === 401) throw expired('登录已过期，请重新登录');
  if (!res.ok || !res.body) throw new ApiError(res.status, `对话接口异常（HTTP ${res.status}）`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const flush = (raw) => {
    let event = 'message';
    const data = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trim());
    }
    if (!data.length) return;
    try {
      onEvent(event, JSON.parse(data.join('\n')));
    } catch {
      /* 单帧解析失败忽略 */
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx = buffer.indexOf('\n\n');
    while (idx >= 0) {
      flush(buffer.slice(0, idx));
      buffer = buffer.slice(idx + 2);
      idx = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) flush(buffer);
}
