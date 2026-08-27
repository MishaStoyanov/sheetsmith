import { useState } from 'react';
import { themes } from './theme.js';
import { login } from './authApi.js';

const mono = "'JetBrains Mono', monospace";

/**
 * The whole app when nobody is signed in. Styled from the same theme tokens as everything else —
 * the primitives this will be rebuilt on do not exist yet, so the inline styles here match the
 * idiom of the panels around them rather than inventing a second one.
 */
export default function LoginScreen({ theme, onSignedIn }) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const submit = async (e) => {
    e.preventDefault();
    if (!name.trim() || !password || busy) return;
    setBusy(true);
    setError(null);
    try {
      const auth = await login(name.trim(), password, rememberMe);
      onSignedIn(auth.user);
    } catch (err) {
      setError(err.message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  const field = {
    width: '100%', height: 38, padding: '0 12px', borderRadius: 8,
    border: '1px solid var(--border-strong)', background: 'var(--surface-2)',
    color: 'var(--text)', fontFamily: 'inherit', fontSize: 14, boxSizing: 'border-box',
  };

  return (
    <div style={{
      ...themes[theme], minHeight: '100vh', background: 'var(--canvas)',
      fontFamily: "'Instrument Sans', system-ui, sans-serif", color: 'var(--text)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      boxSizing: 'border-box',
    }}>
      <form
        onSubmit={submit}
        style={{
          width: '100%', maxWidth: 380, border: '1px solid var(--border-strong)', borderRadius: 16,
          background: 'var(--surface)', boxShadow: '0 10px 30px var(--shadow)', padding: '28px 26px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 22 }}>
          <div style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--accent)', flexShrink: 0 }} />
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>SheetSmith</span>
        </div>

        <label style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>
          Username
        </label>
        <input
          value={name}
          onChange={e => setName(e.target.value)}
          autoFocus
          autoComplete="username"
          style={{ ...field, fontFamily: mono, marginBottom: 14 }}
        />

        <label style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>
          Password
        </label>
        <input
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          autoComplete="current-password"
          style={{ ...field, fontFamily: mono, marginBottom: 14 }}
        />

        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-dim)', marginBottom: 18, cursor: 'pointer' }}>
          <input type="checkbox" checked={rememberMe} onChange={e => setRememberMe(e.target.checked)} />
          Remember me for 30 days
        </label>

        {error && (
          <div style={{
            marginBottom: 14, padding: '9px 12px', borderRadius: 8, fontSize: 13,
            background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)',
          }}>
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={busy || !name.trim() || !password}
          style={{
            width: '100%', height: 40, borderRadius: 9, border: 'none',
            background: 'var(--accent)', color: 'var(--on-accent)', fontFamily: 'inherit',
            fontSize: 14, fontWeight: 600,
            cursor: busy || !name.trim() || !password ? 'default' : 'pointer',
            opacity: busy || !name.trim() || !password ? 0.6 : 1,
          }}
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
