import { useRef, useState } from 'react';
import { ImageViewer, Toast } from 'antd-mobile';
import { speech } from '../voice';
import { formatDuration, clockLabel } from '../time';

/** 声波条：纯装饰，但让「语音消息」一眼可辨。 */
function Bars({ count = 13, playing = false }) {
  return (
    <span className={`voice__bars${playing ? ' is-on' : ''}`}>
      {Array.from({ length: count }, (_, i) => (
        <i key={i} style={{ height: `${4 + ((i * 5) % 11)}px`, animationDelay: `${(i % 5) * 0.12}s` }} />
      ))}
    </span>
  );
}

/** 文字长度估时长：中文 TTS 约 4 字/秒，只用于气泡上那个「12″」。 */
function secondsOf(text) {
  const chars = String(text || '').replace(/\s/g, '').length;
  return Math.max(1, Math.round(chars / 4.2));
}

/**
 * 长按手势（微信那套：按住 0.5 秒弹菜单，松手不触发播放）。
 *
 * 用 pointer 事件而不是 touch：桌面上拿鼠标按住也能用，调试方便。
 */
function useLongPress(onLongPress, ms = 500) {
  const timer = useRef(null);
  const fired = useRef(false);
  const clear = () => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  };
  return {
    fired,
    handlers: {
      onPointerDown: () => {
        fired.current = false;
        clear();
        timer.current = setTimeout(() => {
          fired.current = true;
          onLongPress();
        }, ms);
      },
      onPointerUp: clear,
      onPointerLeave: clear,
      onPointerCancel: clear,
      onContextMenu: (e) => e.preventDefault(),
    },
  };
}

/** 气泡上的长按菜单（转文字 / 播放语音 / 取消），定位在气泡上方。 */
function BubbleMenu({ open, label, onAction, onClose }) {
  if (!open) return null;
  return (
    <div className="voice-menu" role="menu">
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onAction();
        }}
      >
        {label}
      </button>
      <button
        type="button"
        className="voice-menu__cancel"
        onClick={(e) => {
          e.stopPropagation();
          onClose();
        }}
      >
        取消
      </button>
    </div>
  );
}

/** 她发来的语音消息：点一下播放 / 暂停，长按弹「转文字」。 */
function AssistantVoiceBubble({ item, playing, onConvert }) {
  const id = item.serverId || item.id;
  const text = item.audioText || item.text || '';
  const seconds = item.durationSec || secondsOf(text);
  const press = useLongPress(() => onConvert());
  const onClick = () => {
    if (press.fired.current) {
      press.fired.current = false;
      return;
    }
    if (playing) speech.stop();
    else speech.speakMessage(id, text);
  };
  return (
    <button type="button" className={`voice is-in${playing ? ' is-playing' : ''}`} onClick={onClick} {...press.handlers}>
      <span className="voice__play">{playing ? '❚❚' : '▶'}</span>
      <Bars playing={playing} />
      <span className="voice__dur">{seconds}″</span>
    </button>
  );
}

/** 你发出去的语音消息：播本地那段录音，长按弹「转文字」。 */
function UserVoiceBubble({ item, onConvert }) {
  const seconds = formatDuration(item.meta?.durationMs) || `${secondsOf(item.text)}″`;
  const [playing, setPlaying] = useState(false);
  const audioRef = useRef(null);
  const press = useLongPress(() => onConvert());

  const play = () => {
    if (press.fired.current) {
      press.fired.current = false;
      return;
    }
    if (playing) {
      audioRef.current?.pause();
      setPlaying(false);
      return;
    }
    if (!item.localAudioUrl) {
      // 刷新后本地那段录音就不在了（只在内存里的 blob），点播放没反应会以为是坏的
      Toast.show({ content: '这段录音只在当次会话里能放，长按可以看转成的文字' });
      return;
    }
    const audio = new Audio(item.localAudioUrl);
    audioRef.current = audio;
    audio.onended = () => setPlaying(false);
    audio.play().then(() => setPlaying(true)).catch(() => setPlaying(false));
  };

  return (
    <button type="button" className={`voice is-out${playing ? ' is-playing' : ''}`} onClick={play} {...press.handlers}>
      <span className="voice__dur">{seconds}</span>
      <Bars playing={playing} count={11} />
      <span className="voice__play">{playing ? '❚❚' : '▶'}</span>
    </button>
  );
}

function MediaBubble({ item }) {
  const isVideo = item.kind === 'video';
  const posterOnly = Boolean(item.meta?.posterOnly);
  return (
    <div className="bubble bubble--media">
      <div
        className="media-wrap"
        onClick={() => {
          if (isVideo && !posterOnly) return;
          ImageViewer.show({ image: item.mediaUrl, closeable: true });
        }}
      >
        {isVideo && !posterOnly ? (
          <video className="media" src={item.mediaUrl} controls playsInline preload="metadata" />
        ) : (
          <>
            <img className="media" src={item.mediaUrl} alt="她发来的照片" loading="lazy" />
            {isVideo && <span className="media__badge">视频占位 · 演示模式</span>}
          </>
        )}
      </div>
      {item.text ? <div className="media__caption">{item.text}</div> : null}
    </div>
  );
}

/** 文字回复：可选一条语音条（语音+文字模式），纯文字模式长按可播放语音。 */
function AssistantText({ item, speaking, canSpeak }) {
  const id = item.serverId || item.id;
  const playing = speaking.id === id && (speaking.state === 'playing' || speaking.state === 'loading');
  const audioText = item.audioText || item.text;
  const lines = String(item.text || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const [menu, setMenu] = useState(false);
  // 纯文字模式没有语音条，长按弹「播放语音」；语音+文字模式已有语音条，不再重复
  const longPressPlay = Boolean(canSpeak && !item.audio);
  const press = useLongPress(() => { if (longPressPlay) setMenu(true); });

  if (!lines.length) return null;

  return (
    <>
      {item.audio ? (
        <div className="bubble bubble--in bubble--voice-only">
          <button
            type="button"
            className={`voice is-in${playing ? ' is-playing' : ''}`}
            onClick={() => (playing ? speech.stop() : speech.speakMessage(id, audioText))}
          >
            <span className="voice__play">{playing ? '❚❚' : '▶'}</span>
            <Bars playing={playing} />
            <span className="voice__dur">{secondsOf(audioText)}″</span>
          </button>
        </div>
      ) : null}
      <div className="text-msg">
        <BubbleMenu
          open={menu}
          label="播放语音"
          onAction={() => { setMenu(false); speech.speakMessage(id, audioText); }}
          onClose={() => setMenu(false)}
        />
        {lines.map((line, index) => (
          <div
            className="bubble bubble--in"
            key={`${id}-${index}`}
            {...(longPressPlay ? press.handlers : {})}
          >
            {line}
            {item.streaming && index === lines.length - 1 ? <span className="caret" /> : null}
          </div>
        ))}
      </div>
    </>
  );
}

/** 一条语音消息 = 语音条 +（长按转出来的）文字。 */
function VoiceMessage({ item, incoming, playing }) {
  const [menu, setMenu] = useState(false);
  const [showText, setShowText] = useState(false);
  const text = item.audioText || item.text || '';

  return (
    <div className={`voice-msg voice-msg--${incoming ? 'in' : 'out'}`}>
      <BubbleMenu open={menu} label="转文字" onAction={() => { setShowText(true); setMenu(false); }} onClose={() => setMenu(false)} />
      {incoming ? (
        <AssistantVoiceBubble item={item} playing={playing} onConvert={() => setMenu(true)} />
      ) : (
        <UserVoiceBubble item={item} onConvert={() => setMenu(true)} />
      )}
      {showText && text ? <div className="voice-text">{text}</div> : null}
    </div>
  );
}

/** 一条聊天记录 / 一个系统提示。 */
export function MessageItem({ item, speaking, ttsReady, onOpenSettings, onAckReminder }) {
  if (item.type === 'reminder') {
    const acked = Boolean(item.meta?.acked);
    return (
      <div className={`reminder-card${acked ? ' is-acked' : ''}`}>
        <div className="reminder-card__head">
          <span className="reminder-card__icon">⏰</span>
          <span className="reminder-card__time">{item.meta?.remindAtText || '到点提醒'}</span>
        </div>
        <div className="reminder-card__body">
          {item.meta?.kindLabel ? <em className="reminder-card__kind">{item.meta.kindLabel}</em> : null}
          <span>{item.text}</span>
        </div>
        {!acked ? (
          <button type="button" className="reminder-card__ack" onClick={() => onAckReminder?.(item.meta?.reminderId)}>
            知道了
          </button>
        ) : (
          <span className="reminder-card__done">已收到 ✓</span>
        )}
      </div>
    );
  }

  if (item.type === 'notice') {
    return (
      <div className={`notice${item.level === 'error' ? ' notice--error' : ''}`}>
        <span className="notice__dot" />
        <span>{item.message}</span>
        {item.service || item.action === 'open-settings' ? (
          <button type="button" onClick={() => onOpenSettings?.(item.service)}>去设置</button>
        ) : null}
      </div>
    );
  }

  const out = item.role === 'user';
  const id = item.serverId || item.id;
  const playing = speaking.id === id && (speaking.state === 'playing' || speaking.state === 'loading');
  // 只听语音：只放语音条，文字靠长按转出来；否则走文字（语音+文字模式会多带一条语音条）
  const voiceOnly = Boolean(item.audio) && item.voiceOnly;

  return (
    <div className={`row row--${out ? 'out' : 'in'}`}>
      {item.kind === 'image' || item.kind === 'video' ? (
        <MediaBubble item={item} />
      ) : out && item.kind === 'voice' ? (
        <VoiceMessage item={item} incoming={false} />
      ) : out ? (
        <div className="bubble bubble--out">{item.text}</div>
      ) : voiceOnly ? (
        <VoiceMessage item={item} incoming playing={playing} />
      ) : (
        <AssistantText item={item} speaking={speaking} canSpeak={ttsReady} />
      )}
      {item.showTime ? <time className="row__time">{clockLabel(item.createdAt)}</time> : null}
    </div>
  );
}
