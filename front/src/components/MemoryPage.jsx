import { useEffect, useState } from 'react';
import { NavBar, Input, Button, Toast, Dialog, DotLoading } from 'antd-mobile';
import { post } from '../api';
import { humanDate } from '../time';

/**
 * 「她记得的事」。
 * 这一页是验收里「能在界面上查看她记住了哪些内容」的落点：
 * 分类展示 + 待回访 + 最近聊过的话题摘要 + 手动添加/删除。
 */
const GROUPS = [
  { key: 'profile', label: '基本信息', icon: '👤' },
  { key: 'preference', label: '喜好与习惯', icon: '🍜' },
  { key: 'relation', label: '在意的人 / 宠物', icon: '🐱' },
  { key: 'plan', label: '计划与事件', icon: '📌' },
  { key: 'other', label: '其它', icon: '📎' },
];

export function MemoryPage({ open, onClose, onChanged }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState('');
  const [adding, setAdding] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await post('/api/v1/memory/overview/get', {});
      setData(res);
    } catch (err) {
      Toast.show({ content: err.message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) load();
  }, [open]);

  const add = async () => {
    const text = draft.trim();
    if (!text || adding) return;
    setAdding(true);
    try {
      await post('/api/v1/memory/fact/create', { text, category: guessCategory(text) });
      setDraft('');
      await load();
      onChanged?.();
      Toast.show({ content: '记住了' });
    } catch (err) {
      Toast.show({ content: err.message });
    } finally {
      setAdding(false);
    }
  };

  const remove = async (fact) => {
    const ok = await Dialog.confirm({
      content: `让她忘掉「${fact.text}」？`,
      confirmText: '忘掉',
      cancelText: '算了',
    });
    if (!ok) return;
    try {
      await post('/api/v1/memory/fact/delete', { factId: fact.id });
      await load();
      onChanged?.();
    } catch (err) {
      Toast.show({ content: err.message });
    }
  };

  const total = data?.total ?? 0;
  const groups = data?.groups ?? {};
  const followUps = data?.followUps ?? { pending: [], asked: [] };

  return (
    <section className={`view view--page${open ? ' is-open' : ''}`}>
      <NavBar onBack={onClose} className="page-nav">她记得的事</NavBar>

      <div className="page-body">
        {loading && !data ? (
          <div className="page-loading"><DotLoading color="primary" /> 正在翻她的小本子…</div>
        ) : null}

        <div className="mem-hero">
          <strong>{total}</strong>
          <span>件事，聊天时会自然提起</span>
        </div>

        <div className="mem-note">
          她只记你说过的长期信息（喜好、身份、计划、在意的人）。说错了可以直接删掉，
          前后矛盾时她会以你最新说的为准。
        </div>

        {followUps.pending.length ? (
          <div className="mem-group">
            <div className="mem-group__head"><i>⏰</i><b>等着问你的事</b><em>{followUps.pending.length}</em></div>
            <div className="mem-list">
              {followUps.pending.map((f) => (
                <div className="mem-followup" key={f.id}>
                  {/* 这里显示的是「她主动来问你」的日子，比事件当天晚一天（考完再问才自然），
                      所以和下面「计划与事件」里的日期不一定是同一天，别让用户以为记错了 */}
                  <span className="mem-followup__date">{humanDate(f.dueDate)} 会问你</span>
                  <span>{f.text}</span>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        {total === 0 && !loading ? (
          <div className="mem-empty">
            <b>还什么都没记住</b>
            聊几句吧，比如「我不吃香菜」「我养了只猫叫年糕」<br />
            也可以直接在下面加一条。
          </div>
        ) : null}

        {GROUPS.map((group) => {
          const list = groups[group.key] || [];
          if (!list.length) return null;
          return (
            <div className="mem-group" key={group.key}>
              <div className="mem-group__head">
                <i>{group.icon}</i>
                <b>{group.label}</b>
                <em>{list.length}</em>
              </div>
              <div className="mem-list">
                {list.map((fact) => (
                  <div className="mem-item" key={fact.id}>
                    <div className="mem-item__main">
                      <div className="mem-item__text">{fact.text}</div>
                      <div className="mem-item__meta">
                        {fact.eventDate ? <span className="mem-item__tag">{humanDate(fact.eventDate)}</span> : null}
                        <span>{fact.source === 'manual' ? '你告诉她的' : '聊天里记住的'}</span>
                        {fact.mentionedCount > 1 ? <span>提过 {fact.mentionedCount} 次</span> : null}
                      </div>
                    </div>
                    <button type="button" className="mem-item__del" onClick={() => remove(fact)} title="让她忘掉">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round">
                        <path d="M4 7h16M9.5 7V5.5A1.5 1.5 0 0 1 11 4h2a1.5 1.5 0 0 1 1.5 1.5V7M6.5 7l1 12a2 2 0 0 0 2 1.9h5a2 2 0 0 0 2-1.9l1-12" />
                      </svg>
                    </button>
                  </div>
                ))}
              </div>
            </div>
          );
        })}

        {data?.episodes?.length ? (
          <div className="mem-group">
            <div className="mem-group__head"><i>💬</i><b>你们最近聊过</b><em>{data.episodes.length}</em></div>
            {data.episodes.map((ep) => (
              <div className="mem-episode" key={ep.id}>
                <time>{String(ep.at).slice(5, 10).replace('-', '月')}日</time>
                {ep.summary}
              </div>
            ))}
          </div>
        ) : null}
      </div>

      <div className="page-foot">
        <Input
          value={draft}
          onChange={setDraft}
          placeholder="想让她记住点什么？"
          onEnterPress={add}
          maxLength={60}
        />
        <Button color="primary" size="small" loading={adding} onClick={add} disabled={!draft.trim()}>
          记住
        </Button>
      </div>
    </section>
  );
}

function guessCategory(text) {
  if (/猫|狗|宠物|妈妈|爸爸|家人|朋友/.test(text)) return 'relation';
  if (/不(吃|喜欢)|喜欢|爱喝|爱吃/.test(text)) return 'preference';
  if (/面试|考试|体检|出差|旅行|生日|周[一二三四五六日天]|\d+月\d+/.test(text)) return 'plan';
  if (/我叫|我住在|我今年|我是|我做|我在.*工作/.test(text)) return 'profile';
  return 'other';
}
