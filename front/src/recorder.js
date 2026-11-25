/**
 * 录音（浏览器 MediaRecorder）。
 *
 * 只负责「录一小段 + 转 base64」：音频不落本地存储，识别成功后即丢弃（隐私优先）。
 *
 * 关键一步：MediaRecorder 出的是 webm/opus（Chrome）或 mp4（Safari），
 * 而豆包「录音文件识别·极速版」只认 wav / mp3 / m4a / ogg，webm 容器直接拒。
 * 所以这里用 WebAudio 把录音解码、重采样成 16kHz 单声道、再编成 WAV 给识别用；
 * 原始 Blob 保留下来，气泡点播放的还是你刚才那段（音质更好）。
 */

const PREFERRED = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];

export class Recorder {
  constructor({ onTick, maxMs = 60000 } = {}) {
    this.onTick = onTick;
    this.maxMs = maxMs;
    this.recorder = null;
    this.stream = null;
    this.chunks = [];
    this.startedAt = 0;
    this.timer = null;
    this.active = false;
  }

  get supported() {
    return Boolean(navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
  }

  async start() {
    if (this.active) return;
    if (!this.supported) {
      // 浏览器只在「安全上下文」里暴露 navigator.mediaDevices：https 或 localhost。
      // 手机用 http://192.168.x.x:5173 打开时它是 undefined —— 看起来像"浏览器不支持"，
      // 其实是地址不安全。这两种情况必须分开说，否则用户会去换浏览器，白折腾。
      if (!window.isSecureContext) {
        throw new Error('录音只在 https 或 localhost 下可用，现在这个地址（http）被浏览器禁用了麦克风');
      }
      throw new Error('这个浏览器不支持录音，换 Chrome / Edge / Safari 试试');
    }

    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
      });
    } catch (err) {
      const name = err?.name || '';
      if (name === 'NotAllowedError' || name === 'SecurityError') throw new Error('麦克风权限被拒绝了，允许后再试');
      if (name === 'NotFoundError') throw new Error('没找到麦克风设备');
      throw new Error('打开麦克风失败，检查一下系统权限');
    }

    const mimeType = PREFERRED.find((t) => MediaRecorder.isTypeSupported?.(t)) || '';
    this.recorder = new MediaRecorder(this.stream, mimeType ? { mimeType } : undefined);
    this.chunks = [];
    this.recorder.addEventListener('dataavailable', (e) => {
      if (e.data?.size > 0) this.chunks.push(e.data);
    });
    this.recorder.start(120);
    this.startedAt = Date.now();
    this.active = true;
    this.timer = setInterval(() => {
      const ms = Date.now() - this.startedAt;
      this.onTick?.(ms);
      if (ms >= this.maxMs) this.stop();
    }, 100);
  }

  async stop() {
    if (!this.active || !this.recorder) return null;
    const durationMs = Date.now() - this.startedAt;
    const recorder = this.recorder;
    const done = new Promise((resolve) => recorder.addEventListener('stop', resolve, { once: true }));
    try {
      recorder.stop();
    } catch {
      /* 已停止 */
    }
    await done;
    this.cleanup();

    const blob = new Blob(this.chunks, { type: recorder.mimeType || 'audio/webm' });
    this.chunks = [];
    if (!blob.size) return null;

    // 先按原格式转 base64（兜底），再尽量转成 16k WAV 给 ASR
    const rawBase64 = await toBase64(blob);
    let asrBase64 = rawBase64;
    let asrMime = blob.type || 'audio/webm';
    try {
      asrBase64 = await toWavBase64(blob);
      asrMime = 'audio/wav';
    } catch (err) {
      console.warn('[recorder] 转 WAV 失败，按原格式上传：', err?.message || err);
    }
    return { blob, mimeType: asrMime, base64: asrBase64, rawBase64, durationMs };
  }

  cancel() {
    try {
      this.recorder?.stop();
    } catch {
      /* 忽略 */
    }
    this.cleanup();
    this.chunks = [];
  }

  cleanup() {
    clearInterval(this.timer);
    this.timer = null;
    this.active = false;
    this.recorder = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }
}

/**
 * 任意浏览器录音 → 16kHz 单声道 16bit PCM 的 WAV Base64。
 *
 * decodeAudioData 只做解码；重采样交给 OfflineAudioContext（它按目标采样率渲染），
 * 比手写线性插值干净，也不用引入第三方库。
 */
async function toWavBase64(blob) {
  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx || !window.OfflineAudioContext) throw new Error('浏览器不支持音频转码');

  const buffer = await blob.arrayBuffer();
  const ctx = new Ctx();
  let decoded;
  try {
    decoded = await ctx.decodeAudioData(buffer.slice(0));
  } finally {
    ctx.close?.();
  }

  const rate = 16000;
  const frames = Math.max(1, Math.ceil(decoded.duration * rate));
  const offline = new OfflineAudioContext(1, frames, rate);
  const source = offline.createBufferSource();
  source.buffer = decoded;
  source.connect(offline.destination);
  source.start();
  const rendered = await offline.startRendering();
  return encodeWavBase64(rendered.getChannelData(0), rate);
}

/** Float32 PCM → WAV(16bit) → Base64。 */
function encodeWavBase64(samples, sampleRate) {
  const bytes = new Uint8Array(44 + samples.length * 2);
  const view = new DataView(bytes.buffer);
  const ascii = (offset, text) => {
    for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i));
  };
  ascii(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  view.setUint32(16, 16, true);        // PCM 子块大小
  view.setUint16(20, 1, true);         // PCM
  view.setUint16(22, 1, true);         // 单声道
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // 字节率
  view.setUint16(32, 2, true);         // 块对齐
  view.setUint16(34, 16, true);        // 位深
  ascii(36, 'data');
  view.setUint32(40, samples.length * 2, true);

  let offset = 44;
  for (let i = 0; i < samples.length; i += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
    offset += 2;
  }

  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

function toBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || '');
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(new Error('音频读取失败'));
    reader.readAsDataURL(blob);
  });
}
