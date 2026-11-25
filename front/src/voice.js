import { post } from './api';
import { splitSentences } from './text';

/**
 * 语音播报（TTS）。
 *
 * 1. **边生成边播**：回复是流式的，整段合成完再播会明显延迟。这里按标点切句，
 *    第一句一凑齐就送去合成，后续句子在播放时并行预取，听感接近连续说话。
 * 2. **点消息重播**：整条消息按句合成（同一句有内存缓存，不重复请求）。
 *
 * 只用一个 <audio> 串行播放，避免多路打架；失败不重试，直接降级为文字。
 */
const cache = new Map();
const CACHE_LIMIT = 40;

class SpeechQueue {
  constructor() {
    this.audio = new Audio();
    this.audio.preload = 'auto';
    this.queue = [];
    this.playing = false;
    this.current = null;
    this.listeners = new Set();
    this.token = 0;

    this.audio.addEventListener('ended', () => this.next());
    this.audio.addEventListener('error', () => {
      this.emit('error', { messageId: this.current?.messageId });
      this.next();
    });
  }

  onState(cb) {
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  }

  emit(state, payload = {}) {
    for (const cb of this.listeners) cb(state, payload);
  }

  beginStream(messageId) {
    let buffer = '';
    const flush = (text) => {
      const clean = text.trim();
      if (clean && clean.length >= 2) this.enqueue(messageId, clean);
    };
    return {
      push: (delta) => {
        buffer += delta;
        const match = buffer.match(/[\s\S]*[。！？!?…～；;\n]/);
        if (match) {
          flush(match[0]);
          buffer = buffer.slice(match[0].length);
        } else if (buffer.length > 60) {
          const idx = Math.max(buffer.lastIndexOf('，'), buffer.lastIndexOf(','));
          if (idx > 20) {
            flush(buffer.slice(0, idx + 1));
            buffer = buffer.slice(idx + 1);
          }
        }
      },
      end: () => {
        if (buffer.trim()) flush(buffer);
        buffer = '';
      },
    };
  }

  speakMessage(messageId, text) {
    this.stop();
    for (const sentence of splitSentences(text)) this.enqueue(messageId, sentence);
    if (!this.playing) this.next();
  }

  enqueue(messageId, text) {
    this.queue.push({ messageId, text });
    if (!this.playing) this.next();
  }

  async next() {
    if (this.playing) return;
    const item = this.queue.shift();
    if (!item) {
      this.current = null;
      this.emit('idle', {});
      return;
    }
    this.current = item;
    this.playing = true;
    const token = ++this.token;
    this.emit('loading', { messageId: item.messageId });

    try {
      const url = await this.resolve(item.text);
      if (token !== this.token) return;
      this.audio.src = url;
      await this.audio.play();
      this.emit('playing', { messageId: item.messageId });
      const pending = this.queue[0];
      if (pending) this.resolve(pending.text).catch(() => {});
    } catch (err) {
      this.playing = false;
      this.current = null;
      this.emit('error', { messageId: item.messageId, message: err?.message || '语音播放失败' });
      this.queue = [];
    }
  }

  async resolve(text) {
    if (cache.has(text)) return cache.get(text);
    const data = await post('/api/v1/speech/tts', { text });
    const binary = atob(data.audioBase64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    const blob = new Blob([bytes], { type: data.contentType || 'audio/mpeg' });
    const url = URL.createObjectURL(blob);
    cache.set(text, url);
    if (cache.size > CACHE_LIMIT) {
      const [oldKey, oldUrl] = cache.entries().next().value;
      URL.revokeObjectURL(oldUrl);
      cache.delete(oldKey);
    }
    return url;
  }

  stop() {
    this.token += 1;
    this.queue = [];
    this.playing = false;
    this.current = null;
    try {
      this.audio.pause();
      this.audio.currentTime = 0;
    } catch {
      /* 忽略 */
    }
    this.emit('idle', {});
  }

  get isSpeaking() {
    return this.playing || this.queue.length > 0;
  }
}

export const speech = new SpeechQueue();
