import { useState } from 'react';
import { themes } from './theme.js';
import Button from './components/Button.jsx';
import Field from './components/Field.jsx';
import { login } from './authApi.js';

/** The whole app when nobody is signed in. */
export default function LoginScreen({ theme, onSignedIn }) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const ready = name.trim() && password;

  const submit = async (e) => {
    e.preventDefault();
    if (!ready || busy) return;
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

        <Field
          label="Username"
          value={name}
          onChange={e => setName(e.target.value)}
          autoFocus
          autoComplete="username"
          monospace
        />

        <Field
          label="Password"
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          autoComplete="current-password"
          monospace
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

        <Button type="submit" variant="primary" size="lg" block disabled={busy || !ready}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  );
}
