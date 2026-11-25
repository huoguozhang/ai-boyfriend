/**
 * 客户端配置（localStorage）。
 *
 * 配置的归属是这台浏览器：
 * - 设置页改完只写这里，服务端不落盘；
 * - 每次请求把它塞进 `X-Client-Settings` 头带上去；
 * - 后端「客户端优先、缺失回落 .env」——所以这里只存用户真正填过的东西，
 *   没填的字段干脆不出现，服务端就会用自己那份。
 *
 * 服务端不会把它的配置回传给我们（避免 Key 泄漏），因此界面上
 * 「已保存 xxx」的掩码只能用本地这份算。
 */

const STORAGE_KEY = 'soulmate.clientSettings.v1';
const HEADER = 'X-Client-Settings';

/** 当前浏览器里存的配置；结构同接口的 {services, persona, prefs}。 */
export function readClientSettings() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return normalize(parsed);
  } catch {
    return normalize(null);
  }
}

/** 合并写入（深一层），返回写后的完整配置。 */
export function saveClientSettings(patch) {
  const current = readClientSettings();
  const next = normalize({
    services: mergeServices(current.services, patch?.services),
    persona: { ...current.persona, ...(patch?.persona || {}) },
    prefs: { ...current.prefs, ...(patch?.prefs || {}) },
  });
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    /* 隐私模式写不进去：这一轮仍按内存里的配置发请求 */
  }
  return next;
}

/** 清空（"恢复服务端默认"按钮用）。 */
export function clearClientSettings() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

/**
 * 请求头。没有任何客户端配置时返回空对象 —— 此时后端完全走 .env，
 * 一个多余字节都不带上去。
 */
export function clientHeaders() {
  const config = readClientSettings();
  if (!config.services && !Object.keys(config.persona || {}).length
      && !Object.keys(config.prefs || {}).length) {
    return {};
  }
  return { [HEADER]: encode(config) };
}

/** 某个服务在本地填过的字段（没有就是空对象）。 */
export function localService(key) {
  return readClientSettings().services?.[key] || {};
}

/** Key 掩码：只露头尾，和界面上的「已保存」文案配套。 */
export function maskKey(key) {
  const value = String(key || '');
  if (!value) return '';
  if (value.length <= 8) return '•'.repeat(value.length);
  return `${value.slice(0, 4)}••••${value.slice(-4)}`;
}

// ------------------------------------------------------------------ 内部

function normalize(raw) {
  const services = {};
  for (const [key, fields] of Object.entries(raw?.services || {})) {
    const clean = {};
    for (const [field, value] of Object.entries(fields || {})) {
      if (value === null || value === undefined || value === '') continue;
      clean[field] = value;
    }
    if (Object.keys(clean).length) services[key] = clean;
  }
  return {
    services,
    persona: raw?.persona || {},
    prefs: raw?.prefs || {},
  };
}

function mergeServices(current, patch) {
  const next = { ...(current || {}) };
  for (const [key, fields] of Object.entries(patch || {})) {
    // 空 Key = 不改：输入框留空表示"继续用原来的"，不能把已存的 Key 抹掉
    const clean = { ...(next[key] || {}) };
    for (const [field, value] of Object.entries(fields || {})) {
      if (field === 'apiKey' && (value === '' || value == null)) continue;
      if (value === null || value === undefined) continue;
      clean[field] = value;
    }
    if (Object.keys(clean).length) next[key] = clean;
  }
  return next;
}

/** 中文要按 UTF-8 编，HTTP 头只认可见 ASCII。 */
function encode(config) {
  const bytes = new TextEncoder().encode(JSON.stringify(config));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
