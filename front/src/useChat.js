import { useCallback, useEffect, useRef, useState } from 'react';
import { post, postStream } from './api';
import { speech } from './voice';

/**
 * 聊天状态机（React hook）。
 *
 * 一条「消息」在 UI 里可能是多个气泡（她连发两条），所以渲染层按 \n 拆分；
 * 流式文本统一写进 `liveIdRef` 指向的那条消息，媒体到达时把它「冻结」再插入，
 * 这样「先发照片、再配一句话」的顺序天然正确。
 */

const uid = () => `m_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;

/** 后端存的 media 是一段 JSON 字符串；解析失败就当没有，不能让一条坏数据把整页聊天气泡炸掉。 */
function parseMedia(raw) {
  if (!raw) return null;
  if (typeof raw === 'object') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function useChat({ getState, onSession, onMemoryChanged }) {
  const [items, setItems] = useState([]);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [speaking, setSpeaking] = useState({ id: null, state: 'idle' });
  const liveIdRef = useRef(null);
  const feederRef = useRef(null);
  const jobRef = useRef(null);

  useEffect(() => {
    const off = speech.onState((state, payload) => {
      setSpeaking({ id: payload?.messageId || null, state });
    });
    return off;
  }, []);

  const setHistory = useCallback((messages) => {
    // 历史里的 media 是后端存的一小段 JSON（语音消息有 inputMode/audio）。
    // 不解析就会把语音气泡渲染成文字 —— 刷新一次形态就变了，用户会以为记录丢了。
    setItems(messages.map((m) => {
      const media = parseMedia(m.media);
      if (media && media.inputMode === 'voice') {
        return {
          ...m,
          type: 'msg',
          kind: 'voice',
          meta: { durationMs: Number(media.audio?.durationMs) || 0 },
        };
      }
      return { ...m, type: 'msg' };
    }));
    liveIdRef.current = null;
  }, []);

  const pushNotice = useCallback((notice) => {
    setItems((prev) => [...prev, { type: 'notice', id: uid(), ...notice }]);
  }, []);

  /** 到点投递来的提醒：渲染成一张卡片，等他点「知道了」。 */
  const pushReminder = useCallback((reminder) => {
    const id = `rem_${reminder.id || uid()}`;
    setItems((prev) => {
      if (prev.some((it) => it.id === id)) return prev;
      return [...prev, {
        type: 'reminder',
        id,
        role: 'assistant',
        kind: 'reminder',
        text: reminder.content || '',
        meta: {
          reminderId: reminder.id,
          remindAtText: reminder.remindAtText || '',
          kindLabel: reminder.kindLabel || '',
          acked: false,
        },
        createdAt: new Date().toISOString(),
      }];
    });
  }, []);

  /** 他点了「知道了」：回调后端把它标记为已送达，并本地熄灭。 */
  const ackReminder = useCallback(async (reminderId) => {
    try {
      await post(`/api/v1/reminders/${reminderId}/ack`, {});
    } catch {
      /* 后端没 ack 成也先本地熄灭，避免一张卡永远点不掉 */
    }
    setItems((prev) => prev.map((it) => {
      if (it.type === 'reminder' && it.meta?.reminderId === reminderId) {
        return { ...it, meta: { ...it.meta, acked: true } };
      }
      return it;
    }));
  }, []);

  const freezeLive = useCallback((patch = {}) => {
    const id = liveIdRef.current;
    if (!id) return null;
    let frozen = null;
    setItems((prev) => prev.map((it) => {
      if (it.id !== id) return it;
      frozen = { ...it, streaming: false, ...patch };
      return frozen;
    }));
    liveIdRef.current = null;
    return frozen;
  }, []);

  const appendDelta = useCallback((delta) => {
    if (!delta) return;
    if (!liveIdRef.current) liveIdRef.current = `live_${uid()}`;
    const id = liveIdRef.current;
    setItems((prev) => {
      const idx = prev.findIndex((it) => it.id === id);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = { ...next[idx], text: next[idx].text + delta };
        return next;
      }
      return [...prev, {
        type: 'msg',
        id,
        role: 'assistant',
        kind: 'text',
        text: delta,
        createdAt: new Date().toISOString(),
        streaming: true,
      }];
    });
  }, []);

  const handleEvent = useCallback((event, data) => {
    const state = getState();
    switch (event) {
      case 'accepted':
        if (data.sessionId) onSession?.(data.sessionId);
        break;

      case 'typing':
        setStatus(data.state === 'on' ? '正在输入…' : '');
        break;

      case 'text-delta':
        appendDelta(data.delta || '');
        break;

      case 'tool-call-start': {
        const label = { send_photo: '她在拍照…', send_video: '她在录视频…', remember: '她在记…', recall: '她在想…' }[data.name];
        if (label) setStatus(label);
        break;
      }

      case 'tool-result':
        setStatus('');
        break;

      case 'media': {
        setStatus('');
        freezeLive(); // 冻结当前文本，保证媒体插在正确位置
        setItems((prev) => [...prev, {
          type: 'msg',
          id: data.messageId || uid(),
          role: 'assistant',
          kind: data.kind === 'video' ? 'video' : 'image',
          text: data.caption || '',
          mediaUrl: data.url,
          meta: { posterOnly: Boolean(data.posterOnly) },
          createdAt: new Date().toISOString(),
        }]);
        break;
      }

      case 'media-pending':
        jobRef.current = data.jobId || null;
        setStatus('视频在生成，先聊点别的…');
        break;

      case 'notice':
        setStatus('');
        pushNotice({
          level: data.level === 'error' || data.kind === 'capability-missing' ? 'warn' : 'info',
          message: data.message,
          service: data.service || null,
        });
        break;

      case 'memory-updated':
        onMemoryChanged?.();
        break;

      case 'done': {
        const text = String(data.text || '');
        const mode = state.prefs?.receiveMode || 'both';
        const ttsReady = Boolean(state.capabilities?.tts?.configured);
        const audio = Boolean(ttsReady && mode !== 'text');

        if (text.trim()) {
          if (!liveIdRef.current) liveIdRef.current = `live_${uid()}`;
          const id = liveIdRef.current;
          setItems((prev) => {
            const idx = prev.findIndex((it) => it.id === id);
            const patch = {
              text,
              streaming: false,
              audio,
              audioText: audio ? text : null,
              serverId: data.messageId || null,
              voiceOnly: mode === 'voice',
            };
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = { ...next[idx], ...patch };
              return next;
            }
            return [...prev, {
              type: 'msg', id, role: 'assistant', kind: 'text', createdAt: new Date().toISOString(), ...patch,
            }];
          });
        } else {
          freezeLive();
        }
        liveIdRef.current = null;
        feederRef.current?.end();
        feederRef.current = null;
        setStatus('');
        break;
      }

      case 'error':
        pushNotice({
          level: 'error',
          message: data.message || '她说不出话来了',
          service: data.service || (data.code === 6001 ? 'llm' : null),
          // 是客户端自己填的配置出的问题，就给个「去设置」的入口，别让用户猜
          action: data.source === 'client' ? 'open-settings' : undefined,
        });
        freezeLive();
        feederRef.current = null;
        setStatus('');
        break;

      default:
        break;
    }
  }, [appendDelta, freezeLive, getState, onMemoryChanged, onSession, pushNotice]);

  const pollVideo = useCallback((jobId) => {
    const startedAt = Date.now();
    const tick = async () => {
      try {
        const data = await post('/api/v1/media/job/get', { jobId });
        if (data.status === 'succeeded') {
          setItems((prev) => [...prev, {
            type: 'msg',
            id: data.message?.id || uid(),
            role: 'assistant',
            kind: 'video',
            text: data.message?.text || '',
            mediaUrl: data.message?.mediaUrl || data.url,
            createdAt: data.message?.createdAt || new Date().toISOString(),
          }]);
          setStatus('');
          return;
        }
        if (data.status === 'failed') {
          pushNotice({ level: 'warn', message: data.error || '视频没生成成功' });
          setStatus('');
          return;
        }
      } catch {
        /* 查询失败继续等下一次 */
      }
      if (Date.now() - startedAt > 5 * 60 * 1000) {
        pushNotice({ level: 'warn', message: '视频等太久了，可能失败了' });
        setStatus('');
        return;
      }
      setTimeout(tick, 3000);
    };
    setTimeout(tick, 2500);
  }, [pushNotice]);

  /**
   * 发送一条消息。
   * @param {string} text
   * @param {{inputMode?: 'text'|'voice', durationMs?: number, blobUrl?: string}} opts
   */
  const send = useCallback(async (text, opts = {}) => {
    const clean = String(text || '').trim();
    if (!clean || busy) return;
    const state = getState();
    if (!state.capabilities?.llm?.configured) {
      pushNotice({ level: 'error', message: '大模型还没配置，先去设置里填接口地址、API Key 和模型名', action: 'open-settings' });
      return;
    }

    setBusy(true);
    setStatus('正在输入…');
    speech.stop();
    jobRef.current = null;

    const inputMode = opts.inputMode === 'voice' ? 'voice' : 'text';
    setItems((prev) => [...prev, {
      type: 'msg',
      id: uid(),
      role: 'user',
      kind: inputMode === 'voice' ? 'voice' : 'text',
      text: clean,
      meta: { durationMs: opts.durationMs || 0 },
      localAudioUrl: opts.blobUrl || null,
      createdAt: new Date().toISOString(),
    }]);

    const useSpeech = Boolean(state.prefs?.autoPlay && state.prefs?.receiveMode !== 'text' && state.capabilities?.tts?.configured);
    feederRef.current = useSpeech ? speech.beginStream(`live_${Date.now()}`) : null;
    const feeder = feederRef.current;

    try {
      await postStream('/api/v1/chat/stream', {
        sessionId: state.sessionId,
        text: clean,
        inputMode,
        audio: inputMode === 'voice' ? { durationMs: opts.durationMs || 0 } : null,
      }, (event, data) => {
        if (event === 'text-delta' && feeder) feeder.push(data.delta || '');
        handleEvent(event, data);
      });
    } catch (err) {
      pushNotice({ level: 'error', message: err.message || '发送失败' });
    } finally {
      setBusy(false);
      setStatus('');
      onMemoryChanged?.();
      setTimeout(() => onMemoryChanged?.(), 2600);
      if (jobRef.current) pollVideo(jobRef.current);
      jobRef.current = null;
    }
  }, [busy, getState, handleEvent, onMemoryChanged, pollVideo, pushNotice]);

  return { items, busy, status, speaking, send, setHistory, pushNotice, pushReminder, ackReminder };
}
