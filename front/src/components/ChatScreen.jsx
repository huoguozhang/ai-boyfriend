import { useEffect, useMemo, useRef } from 'react';
import { MessageItem } from './MessageItem';
import { Composer } from './Composer';
import { dayLabel } from '../time';

/** 聊天主界面：消息流 + 状态条 + 输入区。 */
export function ChatScreen({
  items, busy, status, speaking, person, prefs, capabilities,
  onSend, onOpenSettings, onAckReminder,
}) {
  const scrollerRef = useRef(null);
  const stickToBottom = useRef(true);

  const hasStreamingText = useMemo(
    () => items.some((i) => i.streaming && String(i.text || '').trim()),
    [items],
  );

  const onScroll = () => {
    const el = scrollerRef.current;
    if (!el) return;
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
  };

  // 首屏直接落到底部（历史消息多时不要慢慢滚），之后有新内容再平滑跟随。
  // 关键：历史消息是 bootstrap 异步回来才渲染的，写成 useEffect([]) 会在列表还空的时候
  // 就滚一次，等于没滚——打开 App 会停在中间某条消息上。所以等第一批 item 到齐再定位。
  const didInitialScroll = useRef(false);
  useEffect(() => {
    const el = scrollerRef.current;
    if (!el || didInitialScroll.current || !items.length) return;
    didInitialScroll.current = true;
    el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
  }, [items.length]);

  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    if (stickToBottom.current) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [items, status, busy]);

  const rendered = [];
  let lastDay = null;
  let lastTs = 0;
  for (const item of items) {
    let showTime = false;
    if (item.createdAt) {
      const ts = Date.parse(item.createdAt) || 0;
      const day = String(item.createdAt).slice(0, 10);
      if (day !== lastDay) {
        lastDay = day;
        lastTs = 0;
        rendered.push(<div className="day-divider" key={`day-${day}-${rendered.length}`}>{dayLabel(item.createdAt)}</div>);
      }
      // 只在与上一条间隔超过 10 分钟时显示时间，避免每条消息下面都挂一串数字
      showTime = !lastTs || ts - lastTs > 10 * 60 * 1000;
      lastTs = ts;
    }
    rendered.push(
      <MessageItem
        key={item.id}
        item={{ ...item, showTime }}
        mode={prefs.receiveMode}
        ttsReady={Boolean(capabilities.tts?.configured)}
        speaking={speaking}
        onOpenSettings={onOpenSettings}
        onAckReminder={onAckReminder}
      />,
    );
  }

  return (
    <section className="view view--chat">
      <div
        className="chat-scroll"
        ref={scrollerRef}
        onScroll={onScroll}
        // 图片/视频是异步加载的，加载完会把下面的内容整体推下去。
        // 只在首屏滚一次是不够的（滚的时候图片高度还是 0），
        // 所以每次媒体加载完，只要用户没往上翻，就把位置重新贴到底。
        onLoadCapture={() => {
          const el = scrollerRef.current;
          if (el && stickToBottom.current) el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
        }}
      >
        <div className="chat-list">
          {!items.length ? (
            <div className="welcome">
              <img className="welcome__avatar" src="/avatar.svg" alt="" />
              <h3>我是{person.name}</h3>
              <p>{person.tagline || '随便聊聊吧'}<br />记得跟我说说你的事，我会记住的。</p>
              <div className="welcome__chips">
                {['我今天加班到现在，累死了', '我不吃香菜', '发张照片看看', '下周三我要去面试'].map((text) => (
                  <button type="button" key={text} className="welcome__chip" onClick={() => onSend(text, { inputMode: 'text' })}>
                    {text}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {rendered}

          {busy && !hasStreamingText ? (
            <div className="typing"><span /><span /><span /></div>
          ) : null}
          <div className="chat-bottom-space" />
        </div>
      </div>

      {status ? (
        <div className="statusbar">
          <span className="statusbar__spinner" />
          <span>{status}</span>
        </div>
      ) : null}

      <Composer
        onSend={onSend}
        disabled={busy}
        busy={busy}
        asrReady={Boolean(capabilities.asr?.configured && !capabilities.asr?.mock)}
        asrMock={Boolean(capabilities.asr?.mock)}
        ttsReady={Boolean(capabilities.tts?.configured)}
        onOpenSettings={onOpenSettings}
      />
    </section>
  );
}
