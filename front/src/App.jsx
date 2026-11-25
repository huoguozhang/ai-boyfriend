import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Popup, Toast, DotLoading } from 'antd-mobile';
import { post, get } from './api';
import { clearAuth, onUnauthorized, readAuth } from './auth';
import { saveClientSettings } from './clientSettings';
import { useChat } from './useChat';
import { ChatScreen } from './components/ChatScreen';
import { LoginPage } from './components/LoginPage';
import { MemoryPage } from './components/MemoryPage';
import { SettingsPage } from './components/SettingsPage';
import { speech } from './voice';

const RECEIVE_LABEL = { text: '只收文字', both: '文字 + 语音', voice: '只听语音' };

export default function App() {
  // 登录态：没有就直接渲染登录页（下面 early return），不发任何业务请求
  const [auth, setAuth] = useState(() => readAuth());
  const [boot, setBoot] = useState(null);
  const [settings, setSettings] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [page, setPage] = useState('');
  const [focusService, setFocusService] = useState('');
  const [profileOpen, setProfileOpen] = useState(false);
  const [receiveOpen, setReceiveOpen] = useState(false);
  const [memoryTotal, setMemoryTotal] = useState(0);
  const stateRef = useRef({});

  const {
    items, busy, status, speaking, send, setHistory, pushReminder, ackReminder,
  } = useChat({
    getState: () => stateRef.current,
    onSession: (sessionId) => {
      stateRef.current = { ...stateRef.current, sessionId };
      setBoot((b) => (b ? { ...b, sessionId } : b));
    },
    onMemoryChanged: () => refreshMemory(),
  });

  // 到点提醒的轮询：每 20 秒拉一次待投递队列，拉到就弹一张提醒卡。
  // 只在本进程内去重（seen），刷新页面后后端仍会再投一次 —— 语义上是对的：他没真正"看到"前，
  // 提醒就该一直在队列里，直到他点「知道了」。
  useEffect(() => {
    if (!auth) return undefined;
    const seen = new Set();
    let stopped = false;
    let timer = null;
    const tick = async () => {
      if (stopped) return;
      try {
        const due = (await get('/api/v1/reminders/due')) || [];
        for (const r of due) {
          if (!seen.has(r.id)) {
            seen.add(r.id);
            pushReminder(r);
          }
        }
      } catch {
        /* 静默：轮询失败下一次再补 */
      }
      timer = setTimeout(tick, 20000);
    };
    timer = setTimeout(tick, 4000);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [auth, pushReminder]);

  const refreshMemory = useCallback(async () => {
    try {
      const res = await post('/api/v1/memory/overview/get', {});
      setMemoryTotal(res.total || 0);
    } catch {
      /* 静默失败，角标不准不影响使用 */
    }
  }, []);

  const refreshSettings = useCallback(async () => {
    const res = await post('/api/v1/settings/get', {});
    setSettings(res);
    stateRef.current = {
      ...stateRef.current,
      capabilities: res.capabilities,
      prefs: res.prefs,
    };
    return res;
  }, []);

  // 启动：一次拿全角色 / 历史 / 能力 / 主动消息。
  // 依赖 auth.token —— 登录成功后同一个 effect 会再跑一次，不用手动调 load()
  useEffect(() => {
    if (!auth) return undefined;
    (async () => {
      try {
        const res = await post('/api/v1/session/bootstrap/get', {});
        setBoot(res);
        setSettings(res.settings);
        setMemoryTotal(res.memory?.total || 0);
        stateRef.current = {
          sessionId: res.sessionId,
          prefs: res.prefs,
          capabilities: res.capabilities,
          person: res.person,
        };
        setHistory(res.messages || []);
        // 支持 ?page=memory / ?page=settings 直达（演示与截图用）
        const wanted = new URLSearchParams(window.location.search).get('page');
        if (wanted === 'memory' || wanted === 'settings') {
          setPage(wanted);
          return;
        }
        // 首次进入且什么都没配：直接把设置页推到用户面前，别让他对着空聊天框发呆
        const noneConfigured = Object.values(res.capabilities || {}).every((c) => !c.configured);
        if (noneConfigured) setTimeout(() => setPage('settings'), 500);
      } catch (err) {
        // 4401 已经由 api.js 广播、下面的监听器负责切回登录页，这里不再报"连不上"
        if (err.code !== 4401) setLoadError(err.message || '启动失败');
      }
    })();
    return undefined;
  }, [auth, setHistory]);

  // 任何一次请求回 4401（token 过期/被换密钥）都会走到这里：清空界面回登录页
  useEffect(() => onUnauthorized(() => {
    setAuth(null);
    setBoot(null);
    setSettings(null);
    setLoadError('');
    setPage('');
  }), []);

  const logout = useCallback(() => {
    clearAuth();
    setAuth(null);
    setBoot(null);
    setSettings(null);
    setPage('');
    setProfileOpen(false);
    setHistory([]);
  }, [setHistory]);

  const openSettings = useCallback((service) => {
    setFocusService(service || '');
    setPage('settings');
  }, []);

  const handleSend = useCallback((text, opts) => send(text, opts), [send]);

  const changeReceiveMode = async (mode) => {
    try {
      saveClientSettings({ prefs: { receiveMode: mode } });
      const res = await post('/api/v1/settings/update', { prefs: { receiveMode: mode } });
      setSettings(res);
      stateRef.current = { ...stateRef.current, prefs: res.prefs };
      setReceiveOpen(false);
      Toast.show({ content: `以后${RECEIVE_LABEL[mode]}`, duration: 1400 });
    } catch (err) {
      Toast.show({ content: err.message });
    }
  };

  const person = boot?.person || { name: '小满', age: 23, city: '杭州', job: '自由插画师' };
  const prefs = settings?.prefs || { receiveMode: 'both', autoPlay: true, proactive: true };
  const capabilities = settings?.capabilities || {};
  const llmOff = !capabilities.llm?.configured;

  const presence = useMemo(() => {
    if (status) return status;
    if (busy) return '正在输入…';
    return speech.isSpeaking ? '正在说话…' : '在线';
  }, [busy, status, speaking]);

  if (!auth) {
    return <LoginPage onSuccess={setAuth} />;
  }

  if (loadError) {
    return (
      <div className="fatal">
        <h1>连不上服务</h1>
        <p>{loadError}</p>
        <pre>cd server && npm start</pre>
        <p className="fatal__hint">启动后刷新本页；如果换了端口，请同步 front/vite.config.js 的代理目标。</p>
      </div>
    );
  }

  if (!boot || !settings) {
    return (
      <div className="splash">
        <img src="/avatar.svg" alt="" />
        {/* DotLoading 渲染的是 <div>，不能用 <p> 包，否则触发 validateDOMNesting */}
        <div className="splash__hint">
          <DotLoading color="primary" />
          <span>正在连接…</span>
        </div>
      </div>
    );
  }

  return (
    <>
      <div className="ambient" aria-hidden="true">
        <span className="ambient__blob ambient__blob_a" />
        <span className="ambient__blob ambient__blob_b" />
        <span className="ambient__hint">小满 · AI 数字人 Demo</span>
      </div>

      <div className="phone">
        <div className="phone__notch" aria-hidden="true" />

        <header className="topbar">
          <button type="button" className="who" onClick={() => setProfileOpen(true)}>
            <span className="avatar">
              <img src="/avatar.svg" alt={person.name} />
              <span className={`avatar__dot${busy ? ' is-away' : ''}`} />
            </span>
            <span className="who__text">
              <strong>{person.name}</strong>
              <em>{presence}</em>
            </span>
          </button>

          <div className="topbar__actions">
            <button type="button" className="pill" onClick={() => setReceiveOpen(true)}>
              {RECEIVE_LABEL[prefs.receiveMode] || '文字 + 语音'}
            </button>
            <button type="button" className="icon-btn" onClick={() => setPage('memory')} title="她记得的事">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                <path d="M9.5 3.5A3 3 0 0 0 6.5 6.5 3 3 0 0 0 4 9.5a3 3 0 0 0 1.2 2.4A3 3 0 0 0 4 14.5a3 3 0 0 0 2.5 3 3 3 0 0 0 3 3 3 3 0 0 0 3-3v-11a3 3 0 0 0-3-3Z" />
                <path d="M14.5 3.5a3 3 0 0 1 3 3 3 3 0 0 1 2.5 3 3 3 0 0 1-1.2 2.4 3 3 0 0 1 1.2 2.6 3 3 0 0 1-2.5 3 3 3 0 0 1-3 3" />
              </svg>
              {memoryTotal > 0 ? (
                <span className="icon-btn__badge">{memoryTotal > 99 ? '99+' : memoryTotal}</span>
              ) : null}
            </button>
            <button type="button" className="icon-btn" onClick={() => openSettings()} title="设置">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="3.2" />
                <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" />
              </svg>
            </button>
          </div>
        </header>

        <main className="stage">
          <ChatScreen
            items={items}
            busy={busy}
            status={status}
            speaking={speaking}
            person={person}
            prefs={prefs}
            capabilities={capabilities}
            onSend={handleSend}
            onOpenSettings={openSettings}
            onAckReminder={ackReminder}
            llmOff={llmOff}
          />

          <MemoryPage
            open={page === 'memory'}
            onClose={() => setPage('')}
            onChanged={refreshMemory}
          />

          <SettingsPage
            open={page === 'settings'}
            focus={focusService}
            onClose={() => setPage('')}
            settings={settings}
            user={auth.user || boot?.user}
            onLogout={logout}
            onSaved={(res) => {
              setSettings(res);
              stateRef.current = {
                ...stateRef.current,
                capabilities: res.capabilities,
                prefs: res.prefs,
              };
            }}
          />
        </main>

        {/* 她的资料 */}
        <Popup visible={profileOpen} onMaskClick={() => setProfileOpen(false)} bodyStyle={{ borderTopLeftRadius: 24, borderTopRightRadius: 24 }}>
          <div className="profile">
            <div className="sheet__grab" />
            <img className="profile__avatar" src="/avatar.svg" alt="" />
            <h3>{person.name}</h3>
            <p className="profile__meta">{person.age} · {person.city} · {person.job}</p>
            <p className="profile__tagline">{person.tagline}</p>
            <dl className="profile__facts">
              <div><dt>外形</dt><dd>{person.appearance || '—'}</dd></div>
              <div><dt>声音</dt><dd>{person.voiceHint || '—'}</dd></div>
              <div><dt>记忆</dt><dd>她记得 {memoryTotal} 件关于你的事</dd></div>
            </dl>
            <div className="profile__actions">
              <button type="button" className="btn btn--ghost" onClick={() => { setProfileOpen(false); setPage('memory'); }}>她记得什么</button>
              <button type="button" className="btn" onClick={() => { setProfileOpen(false); openSettings(); }}>服务设置</button>
            </div>
          </div>
        </Popup>

        {/* 收消息方式 */}
        <Popup visible={receiveOpen} onMaskClick={() => setReceiveOpen(false)} position="bottom" bodyStyle={{ borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: '10px 18px 26px' }}>
          <div className="sheet__grab" />
          <h3 className="popup-title">她的回复怎么给你</h3>
          {[
            { value: 'text', title: '只收文字', desc: '安静，适合上班摸鱼' },
            { value: 'both', title: '文字 + 语音', desc: '看得见也听得见（默认）' },
            { value: 'voice', title: '只听语音', desc: '像真的在打电话，文字可点开' },
          ].map((opt) => (
            <button
              type="button"
              key={opt.value}
              className={`receive-option${prefs.receiveMode === opt.value ? ' is-on' : ''}`}
              onClick={() => changeReceiveMode(opt.value)}
            >
              <div>
                <b>{opt.title}</b>
                <span>{opt.desc}</span>
              </div>
              {prefs.receiveMode === opt.value ? <em>✓</em> : null}
            </button>
          ))}
          {!capabilities.tts?.configured ? (
            <p className="field__hint" style={{ marginTop: 10 }}>
              提示：语音合成还没配置，选「语音」也只会显示文字。
              <a href="#settings" onClick={(e) => { e.preventDefault(); setReceiveOpen(false); openSettings('tts'); }}> 去配置</a>
            </p>
          ) : null}
        </Popup>
      </div>
    </>
  );
}
