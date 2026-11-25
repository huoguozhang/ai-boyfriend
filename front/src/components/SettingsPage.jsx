import { useEffect, useMemo, useState } from 'react';
import { NavBar, Button, Input, Switch, Toast, Tag } from 'antd-mobile';
import { post } from '../api';
import { saveClientSettings, localService, maskKey, clearClientSettings } from '../clientSettings';

/**
 * 设置页。
 *
 * 五类能力各自一张卡：协议 / 接口地址 / API Key / 模型名 / 能力参数 + 测试连接。
 *
 * <b>配置存在这台浏览器里</b>（localStorage），保存时只写本地，顺手调一次
 * `/settings/update` 拿回「按你这份配置算出来的视图」。服务端不落盘、也不把它那份
 * 配置回传（Key 不外泄）——所以界面上「已保存」的掩码只能用本地这份算。
 *
 * 语义是「客户端优先、缺失回落」：哪个字段留空，服务端就用它自己 .env 里的。
 */

const FIELD_LABELS = {
  baseUrl: '接口地址 baseUrl',
  apiKey: 'API Key',
  model: '模型名 model',
  temperature: '随机性 temperature',
  maxTokens: '最大输出 maxTokens',
  language: '识别语言',
  voice: '音色 voice',
  format: '音频格式',
  speed: '语速',
  size: '尺寸',
  duration: '时长（秒）',
};

const NUMERIC = new Set(['temperature', 'maxTokens', 'speed', 'duration']);
const SERVICE_ICON = { llm: '🧠', asr: '🎙', tts: '🔊', image: '📷', video: '🎬' };
const DEFAULT_PROTOCOLS = [
  { value: 'openai', label: 'OpenAI 兼容' },
  { value: 'mock', label: '演示' },
];

/**
 * 后端 `servicesMeta` 的兼容层。
 *
 * 老契约（Node 版）会带 label/desc/protocols/fields，Java 版目前只回
 * `{key, name, hint, required}`。这里按服务补一份本地元信息：后端给了就用后端的，
 * 没给就用这里的，避免 `meta.protocols.map` 直接炸掉整页（设置页白屏的根因）。
 */
const SERVICE_META_FALLBACK = {
  llm: {
    label: '大模型',
    desc: '她说话靠它',
    protocols: DEFAULT_PROTOCOLS,
    fields: ['baseUrl', 'apiKey', 'model'],
    hint: '填 OpenAI 兼容端点的 /v1 根路径、Key 与模型名（如火山方舟 Agent Plan）。',
  },
  asr: {
    label: '语音识别',
    desc: '把你说的转成文字',
    // ark 是方舟多模态（音频当 input_audio 发 chat/completions，与 LLM 共用一把 Key），
    // volcengine 是「语音技术」录音文件识别（要单独的 openspeech Key）
    protocols: [
      { value: 'ark', label: '方舟多模态' },
      { value: 'volcengine', label: '火山语音技术' },
      ...DEFAULT_PROTOCOLS,
    ],
    fields: ['baseUrl', 'apiKey', 'model'],
    hint: '对应 .env 的 ASR_*。推荐 ark：与 LLM 同一把 Key，不用另开账号；mock 只返回固定文本。',
  },
  tts: {
    label: '语音合成',
    desc: '把她的回复读出来',
    protocols: [{ value: 'volcengine', label: '火山 volcengine' }, ...DEFAULT_PROTOCOLS],
    fields: ['baseUrl', 'apiKey', 'model', 'voice'],
    hint: '对应 .env 的 TTS_*；音色与 resource id 必须配套（*_uranus_bigtts 配 seed-tts-2.0）。',
  },
  image: {
    label: '图像生成',
    desc: '她给你发的照片',
    protocols: DEFAULT_PROTOCOLS,
    fields: ['baseUrl', 'apiKey', 'model'],
    hint: '对应 .env 的 IMAGE_*；填 mock 时出本地占位图。',
  },
  video: {
    label: '视频生成',
    desc: '她给你发的小视频',
    protocols: DEFAULT_PROTOCOLS,
    fields: ['baseUrl', 'apiKey', 'model'],
    hint: '对应 .env 的 VIDEO_*；生成是异步任务，前端会轮询结果。',
  },
};

function normalizeMeta(m) {
  const fallback = SERVICE_META_FALLBACK[m.key] || {};
  const protocols = Array.isArray(m.protocols) && m.protocols.length ? m.protocols : fallback.protocols;
  const fields = Array.isArray(m.fields) && m.fields.length ? m.fields : fallback.fields;
  return {
    key: m.key,
    label: m.label || m.name || fallback.label || m.key,
    desc: m.desc || fallback.desc || '',
    protocols: protocols?.length ? protocols : DEFAULT_PROTOCOLS,
    fields: fields?.length ? fields : ['baseUrl', 'apiKey', 'model'],
    // 后端已回字段说明时优先用它（老后端只有一句短描述，那种情况走本地兜底）
    hint: m.fieldHint || m.hint || fallback.hint || '',
  };
}

export function SettingsPage({ open, onClose, settings, onSaved, focus, user, onLogout }) {
  const [drafts, setDrafts] = useState({});
  const [openKey, setOpenKey] = useState('llm');
  const [persona, setPersona] = useState(settings.persona);
  const [busyKey, setBusyKey] = useState('');
  const [engine, setEngine] = useState(null);
  const [reloading, setReloading] = useState(false);
  // servicesMeta 来自服务端；用 useMemo 固定引用，否则每次渲染都是新数组 → effect 死循环
  // 顺带把「后端没给全的字段」补齐，卡片渲染不再依赖后端字段完整性
  const meta = useMemo(() => {
    const list = settings.servicesMeta?.length
      ? settings.servicesMeta
      : Object.keys(settings.services || {}).map((key) => ({ key }));
    return list.map(normalizeMeta);
  }, [settings.servicesMeta, settings.services]);

  useEffect(() => {
    if (!open) return;
    if (focus) setOpenKey(focus);
    const next = {};
    for (const m of meta) {
      const svc = settings.services[m.key] || {};
      // 没填过就留空：不替用户选协议，否则点一下保存就等于"配置过"了，服务端那份会被绕开
      const draft = { protocol: svc.protocol || '', apiKey: '' };
      for (const field of m.fields) draft[field] = svc[field] ?? '';
      next[m.key] = draft;
    }
    setDrafts(next);
    setPersona(settings.persona);
    // 记忆引擎状态每次打开都重新拉：mem0 是进程级组件，可能在上次打开后才有结果
    post('/api/v1/memory/engine/get').then((res) => setEngine(res.status)).catch(() => setEngine(null));
  }, [open, settings, meta]);

  const caps = settings.capabilities || {};
  const allOff = useMemo(() => Object.values(caps).every((c) => !c.configured), [caps]);

  const saveService = async (key, { silent = false } = {}) => {
    const draft = drafts[key];
    const payload = { ...draft };
    for (const field of NUMERIC) {
      if (payload[field] !== '' && payload[field] != null) payload[field] = Number(payload[field]);
    }
    // 先落本地：之后的请求（包括下面这个 update）都会自动把它带上
    saveClientSettings({ services: { [key]: payload } });
    const res = await post('/api/v1/settings/update', { services: { [key]: payload } });
    onSaved?.(res);
    if (!silent) Toast.show({ content: '已保存' });
    return res;
  };

  const testService = async (key) => {
    setBusyKey(key);
    try {
      await saveService(key, { silent: true });
      const res = await post('/api/v1/settings/test', { service: key });
      const detail = res.note || (res.sample ? `返回：${res.sample}` : '连接正常');
      Toast.show({ content: res.url ? '能出图，看图 👇' : detail });
      if (res.url) window.open(res.url, '_blank');
    } catch (err) {
      Toast.show({ content: err.message || '测试失败' });
    } finally {
      setBusyKey('');
    }
  };

  const enableDemo = async () => {
    const payload = {};
    for (const key of ['llm', 'asr', 'tts', 'image', 'video']) payload[key] = { enabled: true, protocol: 'mock' };
    saveClientSettings({ services: payload });
    const res = await post('/api/v1/settings/update', { services: payload });
    onSaved?.(res);
    Toast.show({ content: '已开启演示模式，可以直接聊了' });
  };

  const savePrefs = async (patch) => {
    saveClientSettings({ prefs: patch });
    const res = await post('/api/v1/settings/update', { prefs: patch });
    onSaved?.(res);
  };

  const savePersona = async () => {
    saveClientSettings({ persona });
    const res = await post('/api/v1/settings/update', { persona });
    onSaved?.(res);
    Toast.show({ content: '角色已更新，新对话立即生效' });
  };

  /** 手工改完 .env 之后不用重启，重新载入一次即可（嵌入模型这类进程级配置仍需重启）。 */
  const reloadEnv = async () => {
    setReloading(true);
    try {
      const res = await post('/api/v1/settings/reload', {});
      onSaved?.(res);
      const st = await post('/api/v1/memory/engine/get');
      setEngine(st.status);
      Toast.show({ content: '已重新载入 .env' });
    } catch (err) {
      Toast.show({ content: err.message || '载入失败' });
    } finally {
      setReloading(false);
    }
  };

  return (
    <section className={`view view--page${open ? ' is-open' : ''}`}>
      <NavBar onBack={onClose} className="page-nav">设置</NavBar>

      <div className="page-body">
        {allOff ? (
          <div className="demo-cta">
            <h3>还没有配置任何 AI 服务</h3>
            <p>
              可以先开「演示模式」把整个交互走一遍（本地模拟回复与占位图，不联网、不花 Key）；
              验收真实效果时，再到下面对应卡片填你自己的服务。
            </p>
            <Button block color="primary" onClick={enableDemo}>一键开启演示模式</Button>
          </div>
        ) : null}

        <div className="set-section">
          <div className="set-section__head">
            <h3>AI 服务</h3>
            <p>点开卡片填写，保存后立即生效</p>
          </div>

          {meta.map((m) => (
            <ServiceCard
              key={m.key}
              meta={m}
              service={settings.services[m.key]}
              serverReady={Boolean(caps[m.key]?.configured)}
              serverMock={Boolean(caps[m.key]?.mock)}
              draft={drafts[m.key] || {}}
              open={openKey === m.key}
              testing={busyKey === m.key}
              onToggle={() => setOpenKey(openKey === m.key ? '' : m.key)}
              onChange={(patch) => setDrafts((d) => ({ ...d, [m.key]: { ...d[m.key], ...patch } }))}
              onSave={() => saveService(m.key).catch((e) => Toast.show({ content: e.message }))}
              onTest={() => testService(m.key)}
            />
          ))}
        </div>

        <div className="set-section">
          <div className="set-section__head">
            <h3>聊天偏好</h3>
            <p>决定她怎么把消息递给你</p>
          </div>
          <div className="set-card">
            <div className="segmented">
              {[
                { value: 'text', label: '只收文字' },
                { value: 'both', label: '文字 + 语音' },
                { value: 'voice', label: '只听语音' },
              ].map((opt) => (
                <button
                  type="button"
                  key={opt.value}
                  className={settings.prefs.receiveMode === opt.value ? 'is-on' : ''}
                  onClick={() => savePrefs({ receiveMode: opt.value })}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <SwitchRow
              title="自动播放语音"
              desc="她的回复到了就念出来（需要语音合成可用）"
              checked={settings.prefs.autoPlay}
              onChange={(v) => savePrefs({ autoPlay: v })}
            />
            <SwitchRow
              title="允许她主动找你"
              desc="说过的计划到期后，打开 App 她会先问一句"
              checked={settings.prefs.proactive}
              onChange={(v) => savePrefs({ proactive: v })}
            />
          </div>
        </div>

        <div className="set-section">
          <div className="set-section__head">
            <h3>角色设定</h3>
            <p>改完保存，新开一轮对话就按新人设说话</p>
          </div>
          <div className="set-card">
            <div className="field__row">
              <Field label="名字">
                <Input value={persona.name} onChange={(v) => setPersona({ ...persona, name: v })} />
              </Field>
              <Field label="年龄">
                <Input
                  type="number"
                  value={String(persona.age ?? '')}
                  onChange={(v) => setPersona({ ...persona, age: Number(v) || 0 })}
                />
              </Field>
            </div>
            <div className="field__row">
              <Field label="城市">
                <Input value={persona.city} onChange={(v) => setPersona({ ...persona, city: v })} />
              </Field>
              <Field label="职业">
                <Input value={persona.job} onChange={(v) => setPersona({ ...persona, job: v })} />
              </Field>
            </div>
            <Field label="一句话印象">
              <Input value={persona.tagline} onChange={(v) => setPersona({ ...persona, tagline: v })} />
            </Field>
            <Field label="外形（影响生成照片的 prompt）">
              <Input value={persona.appearance} onChange={(v) => setPersona({ ...persona, appearance: v })} />
            </Field>
            <Field label="声音提示（影响音色选择）">
              <Input value={persona.voiceHint} onChange={(v) => setPersona({ ...persona, voiceHint: v })} />
            </Field>
            <Field label="性格">
              <textarea
                rows={3}
                value={persona.personality}
                onChange={(e) => setPersona({ ...persona, personality: e.target.value })}
              />
            </Field>
            <Field label="说话方式">
              <textarea
                rows={3}
                value={persona.speakingStyle}
                onChange={(e) => setPersona({ ...persona, speakingStyle: e.target.value })}
              />
            </Field>
            <Field label="背景故事">
              <textarea
                rows={4}
                value={persona.backstory}
                onChange={(e) => setPersona({ ...persona, backstory: e.target.value })}
              />
            </Field>
            <Button block color="primary" size="middle" onClick={savePersona} style={{ marginTop: 12 }}>
              保存角色
            </Button>
          </div>
        </div>

        <div className="set-section">
          <div className="set-section__head">
            <h3>记忆引擎</h3>
            <p>长期记忆存在本地 mem0 里，不经过任何云服务</p>
          </div>
          <div className="set-card">
            <div className="switch-row">
              <div className="switch-row__text">
                <b>mem0 · {engine?.ready ? '已就绪' : engine?.enabled ? '不可用' : '未启用'}</b>
                <span>
                  {engine?.ready
                    ? `${engine.embedding?.model || ''}（${engine.dims} 维）· 已索引 ${engine.indexedCount} 条`
                    : engine?.error || '正在读取状态…'}
                </span>
              </div>
              <span className={`chip-status${engine?.ready ? ' is-on' : ''}`}>
                {engine?.ready ? '运行中' : '回落'}
              </span>
            </div>
            <p className="field__hint">
              {engine?.ready
                ? '检索走向量相似度；mem0 出问题时自动回落关键词召回，聊天不受影响。'
                : '当前用内置关键词召回兜底，记忆照常保存，只是「换个说法也能想起来」会弱一些。'}
            </p>
            <p className="field__hint">
              配置在服务端 <b>.env</b>：MEM0_* / EMBEDDING_*（向量库落在 data/ 下）
            </p>
            <Button block size="small" color="primary" fill="outline" loading={reloading} onClick={reloadEnv}>
              重新载入 .env
            </Button>
          </div>
        </div>

        <div className="set-section">
          <div className="set-section__head">
            <h3>账号</h3>
            <p>登录态存在这台浏览器（localStorage），token 有效期 7 天</p>
          </div>
          <div className="set-card">
            <div className="switch-row">
              <div className="switch-row__text">
                <b>{user?.nickname || user?.username || '未登录'}</b>
                <span>{user?.username ? `@${user.username}` : '—'}</span>
              </div>
            </div>
            <Button block size="small" color="danger" fill="outline" onClick={onLogout}>
              退出登录
            </Button>
          </div>
        </div>

        <div className="set-version">
          这里填的配置<b>只存在这台浏览器</b>（localStorage），每次请求随请求头带上：
          填了就用你的，没填就用服务端 <b>.env</b> 那份。服务端不保存、也不回传它的配置。<br />
          <button
            type="button"
            className="set-version__reset"
            onClick={() => {
              clearClientSettings();
              post('/api/v1/settings/get', {})
                .then((res) => onSaved?.(res))
                .catch(() => {});
              Toast.show({ content: '已清空本地配置，全部走服务端默认' });
            }}
          >
            清空本地配置，全部走服务端默认
          </button>
        </div>
      </div>
    </section>
  );
}

function ServiceCard({ meta, service, draft, serverReady, serverMock, open, testing,
  onToggle, onChange, onSave, onTest }) {
  // 服务端只回「你自己填过的那部分」：source=client 才是你的，否则是在用后端默认
  const mine = service?.source === 'client';
  const isMock = mine ? service?.protocol === 'mock' : serverMock;
  const configured = mine ? Boolean(service?.protocol || service?.model || service?.baseUrl) : serverReady;
  // Key 的掩码只能用本地这份算 —— 服务端不会把它那把 Key 回传（那才是泄漏）
  const localKey = localService(meta.key).apiKey || '';
  const masked = maskKey(localKey);
  const hasKey = Boolean(localKey);
  const status = isMock ? '演示' : mine ? '已配置（你的）' : serverReady ? '服务端默认' : '未配置';
  return (
    <div className={`svc${open ? ' is-open' : ''}`}>
      <button type="button" className="svc__head" onClick={onToggle}>
        <span className="svc__icon">{SERVICE_ICON[meta.key]}</span>
        <span className="svc__title">
          <b>{meta.label}</b>
          <span>{meta.desc}</span>
        </span>
        <span className={`chip-status${isMock ? ' is-demo' : configured ? ' is-on' : ''}`}>
          {status}
        </span>
        <span className="svc__chev">›</span>
      </button>

      {open ? (
        <div className="svc__body">
          <div className="field">
            <label>协议</label>
            <div className="segmented segmented--wrap">
              {meta.protocols.map((p) => (
                <button
                  type="button"
                  key={p.value}
                  className={draft.protocol === p.value ? 'is-on' : ''}
                  onClick={() => onChange({ protocol: p.value })}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          {draft.protocol !== 'mock' ? (
            <>
              {meta.fields.map((field) => (
                <Field key={field} label={FIELD_LABELS[field] || field}>
                  <Input
                    value={String(draft[field] ?? '')}
                    type={field === 'apiKey' ? 'password' : NUMERIC.has(field) ? 'number' : 'text'}
                    placeholder={
                      field === 'apiKey'
                        ? hasKey
                          ? `已保存 ${masked}，留空则不改`
                          : '留空 = 用服务端已配好的那把'
                        : ''
                    }
                    onChange={(v) => onChange({ [field]: v })}
                  />
                </Field>
              ))}
              <p className="field__hint">{meta.hint}</p>
            </>
          ) : (
            <p className="field__hint">
              演示模式不联网：回复与记忆由本地规则模拟，照片为占位图，语音是提示音。
              走通流程后再回来换成真实服务即可。
            </p>
          )}

          <div className="svc__actions">
            <Button size="small" color="primary" fill="outline" loading={testing} onClick={onTest}>
              测试连接
            </Button>
            <Button size="small" color="primary" onClick={onSave}>
              保存
            </Button>
            {hasKey ? <Tag color="success">Key 存在本机</Tag> : null}
            {!hasKey && serverReady ? <Tag color="default">用服务端默认</Tag> : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
    </div>
  );
}

function SwitchRow({ title, desc, checked, onChange }) {
  return (
    <div className="switch-row">
      <div className="switch-row__text">
        <b>{title}</b>
        <span>{desc}</span>
      </div>
      <Switch checked={checked} onChange={onChange} />
    </div>
  );
}
