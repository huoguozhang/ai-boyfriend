/**
 * 浏览器原生语音识别（Web Speech API）—— 服务端 ASR 没配 Key 时的兜底。
 *
 * 代价先说清楚：这个 API **只吐文字，不给你音频**。所以走这条路时，
 * 用户发出去的是普通文字气泡，「按住说话 → 播放自己那段录音」的形态就没了。
 * 因此它是降级路径：只要服务端 ASR 可用（`capabilities.asr.configured && !mock`），
 * 一律优先走服务端那条（见 Composer）。
 *
 * 兼容性：Chrome / Edge / Safari 都有，但名字带前缀 —— Safari 上是
 * `webkitSpeechRecognition`，Chrome 两个都挂。iOS 上必须 https（和安全上下文同一条规矩）。
 * 微信等 App 内置浏览器大多不暴露这个 API，此时会走"两者都不可用"的提示分支。
 */

function recognizerClass() {
  if (typeof window === 'undefined') return null;
  return window.SpeechRecognition || window.webkitSpeechRecognition || null;
}

/** 这个浏览器有没有原生识别。只判断 API 存在，不判断是否真能识别出东西。 */
export function nativeAsrSupported() {
  return Boolean(recognizerClass());
}

/**
 * 一次识别会话。
 *
 * 用法：start() 之后用户说话，说完（`continuous=false`）浏览器自己 onend，
 * 我们把累计的 final 结果拼起来回调 onResult；中途可以 stop() 提前结束。
 */
export class NativeAsr {
  constructor({ lang = 'zh-CN', onInterim, onResult, onError } = {}) {
    this.SR = recognizerClass();
    this.lang = lang;
    this.onInterim = onInterim;
    this.onResult = onResult;
    this.onError = onError;
    this.rec = null;
    this.active = false;
    this.finals = [];
  }

  get supported() {
    return Boolean(this.SR);
  }

  start() {
    if (!this.SR) throw new Error('这个浏览器不支持原生语音识别');
    if (this.active) return;

    const rec = new this.SR();
    rec.lang = this.lang;
    // 一句话就结束：陪伴场景要的是"说完就发"，不是长时间听写
    rec.continuous = false;
    rec.interimResults = true;
    rec.maxAlternatives = 1;

    this.finals = [];

    rec.onresult = (event) => {
      let interim = '';
      for (let i = event.resultIndex; i < event.results.length; i += 1) {
        const result = event.results[i];
        const text = (result[0] && result[0].transcript) || '';
        if (result.isFinal) {
          if (text.trim()) this.finals.push(text.trim());
        } else {
          interim += text;
        }
      }
      if (interim && this.onInterim) this.onInterim(interim);
    };

    rec.onerror = (event) => {
      this.active = false;
      this.rec = null;
      this.finals = [];
      if (this.onError) this.onError(event.error || 'unknown');
    };

    rec.onend = () => {
      const wasActive = this.active;
      this.active = false;
      this.rec = null;
      const text = this.finals.join('').trim();
      this.finals = [];
      // 手动 abort() 时 active 已经是 false，不再回调，避免"取消还发了消息"
      if (wasActive && this.onResult) this.onResult(text);
    };

    this.rec = rec;
    this.active = true;
    rec.start();
  }

  /** 用户主动结束：剩下的结果会走 onresult → onend。 */
  stop() {
    if (this.rec && this.active) {
      try {
        this.rec.stop();
      } catch {
        // 已经结束了，忽略
      }
    }
  }

  /** 取消（比如手指划走）：不触发 onResult，一个字都不发。 */
  abort() {
    if (this.rec) {
      try {
        this.rec.abort();
      } catch {
        // ignore
      }
    }
    this.active = false;
    this.rec = null;
    this.finals = [];
  }
}

/** 把 Web Speech 的错误码翻译成人话。 */
export function nativeAsrErrorMessage(code) {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return '麦克风权限被拒绝了，允许后再试';
    case 'no-speech':
      return '没听清，再说一遍？';
    case 'audio-capture':
      return '没找到麦克风设备';
    case 'network':
      return '语音识别服务连不上（原生识别要联网）';
    case 'aborted':
      return '';
    default:
      return '语音识别失败，再试一次';
  }
}
