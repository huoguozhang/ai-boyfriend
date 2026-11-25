import { useState } from 'react';
import { Button } from 'antd-mobile';
import { post } from '../api';
import { saveAuth } from '../auth';

/**
 * 登录 / 注册。未登录时 App 直接渲染这一页，进来就没有别的入口。
 *
 * 注册成功后端直接返回 token（不要求再登录一次），这里同样直接进主界面。
 *
 * 用原生 <input> 而不是 antd-mobile 的 Input：这一页要的是"表单 + 回车提交"，
 * 原生 input 塞进 <form> 就自带回车提交，样式也好按设计稿改；设置页那种密集表单才适合用组件库。
 */
export function LoginPage({ onSuccess }) {
  const [mode, setMode] = useState('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const isRegister = mode === 'register';

  const switchMode = (next) => {
    if (busy || next === mode) return;
    setMode(next);
    setError('');
    setConfirm('');
  };

  const submit = async (event) => {
    event.preventDefault();
    if (busy) return;
    setError('');

    const name = username.trim();
    if (!name || !password) {
      setError('用户名和密码都要填');
      return;
    }
    if (isRegister && password !== confirm) {
      setError('两次输入的密码不一样');
      return;
    }

    setBusy(true);
    try {
      const path = isRegister ? '/api/v1/auth/register' : '/api/v1/auth/login';
      const body = isRegister
        ? { username: name, password, nickname: nickname.trim() || undefined }
        : { username: name, password };
      const result = await post(path, body);
      onSuccess(saveAuth(result));
    } catch (err) {
      // 4401 不会出现在这里（登录接口本身是放行的），其余都是业务错误：直接显示人话
      setError(err.message || '请求失败');
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={submit}>
        <img className="auth__avatar" src="/avatar.svg" alt="" />
        <h1 className="auth__title">小满</h1>
        <p className="auth__subtitle">她记得你说过的每一件事</p>

        <div className="auth__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={!isRegister}
            className={`auth__tab${!isRegister ? ' is-on' : ''}`}
            onClick={() => switchMode('login')}
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={isRegister}
            className={`auth__tab${isRegister ? ' is-on' : ''}`}
            onClick={() => switchMode('register')}
          >
            注册
          </button>
        </div>

        <label className="auth__field">
          <span>用户名</span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="3~24 位，中文/字母/数字"
            autoComplete="username"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            maxLength={24}
            disabled={busy}
          />
        </label>

        <label className="auth__field">
          <span>密码</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={isRegister ? '至少 6 位' : '输入密码'}
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            maxLength={64}
            disabled={busy}
          />
        </label>

        {isRegister ? (
          <>
            <label className="auth__field">
              <span>确认密码</span>
              <input
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                placeholder="再输一次"
                autoComplete="new-password"
                maxLength={64}
                disabled={busy}
              />
            </label>
            <label className="auth__field">
              <span>
                昵称 <em>选填</em>
              </span>
              <input
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="她怎么叫你，默认用用户名"
                maxLength={24}
                disabled={busy}
              />
            </label>
          </>
        ) : null}

        {error ? <p className="auth__error">{error}</p> : null}

        <Button
          block
          color="primary"
          size="large"
          type="submit"
          loading={busy}
          loadingText="稍等…"
          className="auth__submit"
        >
          {isRegister ? '注册并进入' : '登录'}
        </Button>

        <p className="auth__hint">
          账号只存在本机 PostgreSQL 里，密码用 PBKDF2 加盐哈希，不存明文。
        </p>
      </form>
    </div>
  );
}
