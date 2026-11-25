import { useEffect, useRef, useState } from 'react';
import { Toast } from 'antd-mobile';
import { Recorder } from '../recorder';
import { NativeAsr, nativeAsrErrorMessage, nativeAsrSupported } from '../nativeAsr';
import { post } from '../api';
import { formatDuration } from '../time';

/**
 * 输入区：文字 / 语音二选一。
 *
 * 语音同时支持两种手势（需求里两种都提到了）：
 * - **按住说话**：按下开始、松开发送、上滑取消；
 * - **点击录音**：轻点一下开始（松手不结束），再点一下结束并发送。
 * 判断依据是按下到松开的时长（>380ms 视为长按）。
 *
 * 识别走哪条路由「有没有服务端 ASR」决定：
 * - 服务端可用（`asrReady`）→ 录一段 WAV 上传（能保留语音气泡，可回放自己那段）；
 * - 服务端不可用但浏览器有 Web Speech → 原生识别兜底，只出文字（API 不给音频）；
 * - 两者都没有 → 提示去设置。
 */
export function Composer({ onSend, disabled, busy, asrReady, asrMock, ttsReady, onOpenSettings }) {
  const [mode, setMode] = useState('text');
  const [value, setValue] = useState('');
  const [rec, setRec] = useState({ active: false, ms: 0, cancel: false, clickMode: false });
  const [uploading, setUploading] = useState(false);

  const textareaRef = useRef(null);
  const recorderRef = useRef(null);
  const nativeRef = useRef(null);
  // 原生识别只在"服务端这条路不可用"时才接管
  const useNativeAsr = !asrReady && nativeAsrSupported();
  const pressAt = useRef(0);
  const pressY = useRef(0);
  const cancelRef = useRef(false);

  useEffect(() => {
    recorderRef.current = new Recorder({
      onTick: (ms) => setRec((r) => ({ ...r, ms })),
    });
    return () => {
      recorderRef.current?.cancel();
      nativeRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(96, el.scrollHeight)}px`;
  }, [value]);

  const submit = () => {
    const text = value.trim();
    if (!text) return;
    setValue('');
    onSend(text, { inputMode: 'text' });
  };

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  // ------------------------------------------------------------ 录音

  const startNativeRecording = () => {
    if (!nativeRef.current) {
      nativeRef.current = new NativeAsr({
        onResult: (text) => {
          setRec({ active: false, ms: 0, cancel: false, clickMode: false });
          if (!text) {
            Toast.show({ content: '没听清，再说一遍？' });
            return;
          }
          // 原生识别拿不到音频，只能当文字发；传 inputMode='voice' 会渲染出播不了的语音条
          onSend(text, { inputMode: 'text' });
        },
        onError: (code) => {
          setRec({ active: false, ms: 0, cancel: false, clickMode: false });
          const message = nativeAsrErrorMessage(code);
          if (message) Toast.show({ content: message });
          if (code === 'not-allowed' || code === 'service-not-allowed') {
            onOpenSettings?.('asr');
          }
        },
      });
    }
    try {
      nativeRef.current.start();
      setRec({ active: true, ms: 0, cancel: false, clickMode: true });
    } catch {
      Toast.show({ content: '原生语音识别启动失败，再试一次' });
    }
  };

  const startRecording = async () => {
    if (!asrReady) {
      // mock 不是"能用"，是每次都会返回同一句写死的话 —— 说清楚，否则听起来像"识别得很差"
      Toast.show({
        content: asrMock
          ? '语音识别还是演示模式（每次都返回同一句），先在设置里填真实 Key'
          : '语音识别还没配置，先在设置里补上',
      });
      onOpenSettings?.('asr');
      return;
    }
    cancelRef.current = false;
    try {
      await recorderRef.current.start();
      pressAt.current = Date.now();
      setRec({ active: true, ms: 0, cancel: false, clickMode: false });
    } catch (err) {
      Toast.show({ content: err.message || '录音失败' });
    }
  };

  const finishRecording = async ({ cancelled = false } = {}) => {
    const result = await recorderRef.current.stop();
    setRec({ active: false, ms: 0, cancel: false, clickMode: false });
    if (cancelled || !result) return;
    if (result.durationMs < 600) {
      Toast.show({ content: '说得太短了，再试一次' });
      return;
    }

    setUploading(true);
    try {
      const data = await post('/api/v1/speech/asr', {
        audioBase64: result.base64,
        mimeType: result.mimeType,
        durationMs: result.durationMs,
      });
      if (data?.mock) {
        // 兜底：后端在演示模式下会回一句固定文本，绝不能当成"你刚说的话"发出去
        Toast.show({ content: '语音识别是演示模式，返回的不是你说的话，先在设置里填真实 Key' });
        onOpenSettings?.('asr');
        return;
      }
      // 必须 trim：模型偶尔返回空格/换行，`!data.text` 判不出来，
      // 结果前端乐观插入一条气泡、后端 trim 后拒收 —— 用户看到一条刷新就没了的"幽灵消息"
      const recognized = (data?.text || '').trim();
      if (!recognized) {
        Toast.show({ content: '没听清，再说一遍？' });
        return;
      }
      onSend(recognized, {
        inputMode: 'voice',
        durationMs: result.durationMs,
        blobUrl: URL.createObjectURL(result.blob),
      });
    } catch (err) {
      Toast.show({ content: err.message || '语音识别失败' });
      if (err.code === 6011) onOpenSettings?.('asr');
    } finally {
      setUploading(false);
    }
  };

  const onHoldDown = (e) => {
    e.preventDefault();
    if (disabled) {
      // 之前这里是裸 return：点了没反应，用户只会以为按钮坏了
      Toast.show({ content: '她还在说，等这条说完再按住' });
      return;
    }
    if (useNativeAsr) {
      // 原生识别是"点一下开始、再点一下结束"，没有按住手势（它自己会在一句话后结束）
      if (rec.active) {
        nativeRef.current?.stop();
      } else {
        startNativeRecording();
      }
      return;
    }
    if (rec.active && rec.clickMode) {
      finishRecording({ cancelled: cancelRef.current });
      return;
    }
    if (!rec.active) startRecording();
  };

  const onHoldMove = (e) => {
    if (useNativeAsr || !rec.active || rec.clickMode) return;
    const dy = e.clientY - pressY.current;
    if (pressY.current === 0) pressY.current = e.clientY;
    const shouldCancel = e.clientY - pressY.current < -60;
    if (shouldCancel !== cancelRef.current) {
      cancelRef.current = shouldCancel;
      setRec((r) => ({ ...r, cancel: shouldCancel }));
    }
    void dy;
  };

  const onHoldUp = () => {
    if (useNativeAsr) return;
    if (!rec.active) return;
    const held = Date.now() - pressAt.current;
    if (held < 380 && !rec.clickMode) {
      // 轻点：切到「点击结束」模式
      pressY.current = 0;
      setRec((r) => ({ ...r, clickMode: true }));
      return;
    }
    if (rec.clickMode) return; // 点击模式下松手不结束
    finishRecording({ cancelled: cancelRef.current });
  };

  return (
    <>
      <div className="composer">
        <button
          type="button"
          className="composer__mode"
          title={mode === 'text' ? '切到语音' : '切到文字'}
          onClick={() => setMode((m) => (m === 'text' ? 'voice' : 'text'))}
        >
          {mode === 'text' ? '🎤' : '⌨'}
        </button>

        {mode === 'text' ? (
          <div className="composer__field">
            <textarea
              ref={textareaRef}
              rows={1}
              value={value}
              maxLength={1000}
              placeholder={busy ? '她正在说…' : '说点什么…'}
              onKeyDown={onKeyDown}
              onChange={(e) => setValue(e.target.value)}
            />
          </div>
        ) : (
          <button
            type="button"
            className={`composer__hold${rec.active ? ' is-active' : ''}`}
            onPointerDown={onHoldDown}
            onPointerMove={onHoldMove}
            onPointerUp={onHoldUp}
            onPointerCancel={onHoldUp}
            onPointerLeave={() => {
              if (rec.active && !rec.clickMode) onHoldUp();
            }}
          >
            {uploading
              ? '识别中…'
              : useNativeAsr
                ? (rec.active ? '正在听…' : '点击 说话')
                : (rec.active ? (rec.clickMode ? '点击 结束' : '松开 发送') : '按住 说话')}
          </button>
        )}

        {mode === 'text' ? (
          <button type="button" className="composer__send" disabled={disabled || !value.trim()} onClick={submit}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4.5 12 20 4.5 15 20l-3.6-5.4L4.5 12Z" />
            </svg>
          </button>
        ) : null}
      </div>

      {rec.active ? (
        <div className={`recording${rec.cancel ? ' is-cancel' : ''}`}>
          <div className="recording__panel">
            <div className="recording__pulse"><span /><span /><span /></div>
            <div className="recording__time">{formatDuration(rec.ms)}</div>
            <p className="recording__hint">
              {rec.cancel ? '松开 取消' : rec.clickMode ? '点击「结束」发送 · 再点麦克风取消' : '松开 发送 · 上滑 取消'}
            </p>
            <div className="recording__actions">
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => finishRecording({ cancelled: true })}>
                取消
              </button>
              <button type="button" className="btn btn--sm" onClick={() => finishRecording({})}>
                发送
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {!ttsReady ? <div className="composer__tip">语音合成未配置，她现在只能打字</div> : null}
    </>
  );
}
