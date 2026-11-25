/**
 * 登录态（JWT）的本地存储。
 *
 * 和 clientSettings 是两回事：
 * - 这里存的是「你是谁」——token + 用户信息，属于账号，换浏览器就得重新登录；
 * - clientSettings 存的是「你偏好怎么配」——API Key、角色卡，属于这台浏览器。
 *
 * token 放 localStorage 而不是内存：刷新页面不该掉登录。代价是 XSS 能读到它，
 * 这个 Demo 是本地跑的单页应用，没有第三方脚本，可以接受；真要抗 XSS 得用
 * httpOnly cookie + CSRF token，那是另一套工程。
 */

const STORAGE_KEY = 'soulmate.auth.v1';
const UNAUTHORIZED_EVENT = 'soulmate:unauthorized';

function readRaw() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * 当前登录态；没有、或 token 已过期，都返回 null。
 *
 * 过期判断放在客户端只是「少发一次必然失败的请求」，真正的权威仍然是服务端签名校验——
 * 用户改系统时间就能骗过这里，但骗不过服务端。
 */
export function readAuth() {
  const saved = readRaw();
  if (!saved || !saved.token) return null;
  if (saved.expiresAt && saved.expiresAt <= Date.now()) {
    clearAuth();
    return null;
  }
  return saved;
}

export function readUser() {
  return readAuth()?.user || null;
}

/** 存下登录/注册返回的 {token, tokenType, expiresIn, user}。 */
export function saveAuth(result) {
  const auth = {
    token: result.token,
    user: result.user,
    // 有效期换算成本地绝对时间，省得每次请求都算一遍
    expiresAt: Date.now() + (Number(result.expiresIn) || 0) * 1000,
  };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(auth));
  } catch {
    /* 隐私模式下写不进去：这一轮仍可用内存里的 token，刷新就掉登录 */
  }
  return auth;
}

export function clearAuth() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

/** 请求头：没登录返回空对象（登录/注册接口本身不需要带）。 */
export function authHeaders() {
  const auth = readAuth();
  return auth ? { Authorization: `Bearer ${auth.token}` } : {};
}

/**
 * 服务端说 4401 时统一走这里：清掉登录态并广播，App 收到就切回登录页。
 *
 * 用 window 事件而不是回调链，是因为 4401 可能发生在任何一次请求里
 * （聊天、记忆、设置…），逐个把 onUnauthorized 传下去会污染所有组件的 props。
 */
export function notifyUnauthorized(reason) {
  clearAuth();
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: reason || '' }));
}

export function onUnauthorized(handler) {
  const listener = (event) => handler(event.detail);
  window.addEventListener(UNAUTHORIZED_EVENT, listener);
  return () => window.removeEventListener(UNAUTHORIZED_EVENT, listener);
}
