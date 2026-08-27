import { useState } from 'react';
import Button from './components/Button.jsx';

const mono = "'JetBrains Mono', monospace";

/**
 * Everything around the page: the header that was already there, and a left column that collapses
 * to icons.
 *
 * The tabs are built from what the instance actually has rather than hidden with CSS — a menu entry
 * that leads somewhere refusing to answer is worse than no entry at all.
 */
export default function AppShell({
  theme,
  onToggleTheme,
  providerMode,
  user,
  route,
  onNavigate,
  onOpenSettings,
  onSignOut,
  tabs,
  children,
}) {
  const [expanded, setExpanded] = useState(() => localStorage.getItem('ss-nav') !== 'collapsed');

  const toggleNav = () => {
    setExpanded(next => {
      // Remembered beside the theme, and for the same reason: it is a choice about this person's
      // screen, not about the instance.
      localStorage.setItem('ss-nav', next ? 'collapsed' : 'expanded');
      return !next;
    });
  };

  const width = expanded ? 188 : 56;

  return (
    <div style={{ minHeight: '100vh', background: 'var(--canvas)', fontFamily: "'Instrument Sans', system-ui, sans-serif", color: 'var(--text)', boxSizing: 'border-box' }}>

      {/* Header */}
      <div style={{ height: 54, display: 'flex', alignItems: 'center', gap: 12, padding: '0 28px', background: 'var(--surface)', borderBottom: '1px solid var(--border)', position: 'relative', zIndex: 2 }}>
        <div style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--accent)', flexShrink: 0 }} />
        <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>SheetSmith</span>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: 'var(--text-faint)', display: 'flex', alignItems: 'center', gap: 5 }}>
          <span style={{ color: 'var(--accent)' }}>⟡</span> {providerMode === 'CLOUD' ? 'Cloud mode' : 'Local mode'}
        </span>
        <Button onClick={onToggleTheme} style={{ marginLeft: 8 }}>
          {theme === 'light' ? 'Dark' : 'Light'}
        </Button>
        {user && (
          <Button onClick={onSignOut} title={`Signed in as ${user.name}`} style={{ marginLeft: 8 }}>
            Sign out
          </Button>
        )}
      </div>

      {/* Shown to the person who has signed in, never to a stranger through /api/capabilities:
          "this instance still has its default password" is not a sentence to hand an anonymous
          caller. */}
      {user?.mustChangePassword && (
        <div style={{ padding: '10px 28px', background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 13, borderBottom: '1px solid var(--border)' }}>
          This account still has its default password. Change it before anyone else uses this instance.
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'stretch' }}>

        {/* Left menu */}
        <nav style={{ width, flexShrink: 0, borderRight: '1px solid var(--border)', background: 'var(--surface)', transition: 'width 0.15s ease', display: 'flex', flexDirection: 'column', padding: '10px 8px', gap: 2, position: 'sticky', top: 0, alignSelf: 'flex-start', minHeight: 'calc(100vh - 54px)' }}>
          {tabs.map(tab => {
            const active = tab.route === route;
            return (
              <button
                key={tab.route}
                onClick={() => onNavigate(tab.route)}
                title={expanded ? undefined : tab.label}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10, height: 36, padding: '0 10px',
                  borderRadius: 8, border: 'none', cursor: 'pointer', textAlign: 'left',
                  background: active ? 'var(--accent-soft)' : 'transparent',
                  color: active ? 'var(--accent-text)' : 'var(--text-dim)',
                  fontFamily: 'inherit', fontSize: 13.5, fontWeight: active ? 600 : 500,
                  overflow: 'hidden', whiteSpace: 'nowrap',
                }}
              >
                <span style={{ fontSize: 15, width: 18, flexShrink: 0, textAlign: 'center' }}>{tab.icon}</span>
                {expanded && tab.label}
              </button>
            );
          })}

          <div style={{ flex: 1 }} />

          <button
            onClick={onOpenSettings}
            title={expanded ? undefined : 'Settings'}
            style={{ display: 'flex', alignItems: 'center', gap: 10, height: 36, padding: '0 10px', borderRadius: 8, border: 'none', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13.5, fontWeight: 500, cursor: 'pointer', textAlign: 'left', overflow: 'hidden', whiteSpace: 'nowrap' }}
          >
            <span style={{ fontSize: 15, width: 18, flexShrink: 0, textAlign: 'center' }}>⚙</span>
            {expanded && 'Settings'}
          </button>

          <button
            onClick={toggleNav}
            title={expanded ? 'Collapse' : 'Expand'}
            style={{ display: 'flex', alignItems: 'center', gap: 10, height: 32, padding: '0 10px', borderRadius: 8, border: 'none', background: 'transparent', color: 'var(--text-faint)', fontFamily: mono, fontSize: 12, cursor: 'pointer', textAlign: 'left', overflow: 'hidden', whiteSpace: 'nowrap' }}
          >
            <span style={{ fontSize: 13, width: 18, flexShrink: 0, textAlign: 'center' }}>{expanded ? '‹' : '›'}</span>
            {expanded && 'Collapse'}
          </button>
        </nav>

        <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
      </div>
    </div>
  );
}
