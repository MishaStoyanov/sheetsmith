import { useState } from 'react';
import Button from './components/Button.jsx';
import DefaultPasswordNotice from './components/DefaultPasswordNotice.jsx';
import BudgetBar from './components/BudgetBar.jsx';
import BudgetDecisionNotice from './components/BudgetDecisionNotice.jsx';
import { ChevronIcon, SettingsIcon } from './components/NavIcons.jsx';

/**
 * Everything around the page: the header, and a left column that collapses to icons.
 *
 * The layout is a fixed-height column rather than a document that scrolls as a whole. That is not a
 * preference: the nav used to be as tall as the page, so on a long screen the collapse control sat
 * below the fold and had to be scrolled to — a control belonging to the frame, hidden by the
 * contents. Now the frame stays put and only the content pane scrolls.
 *
 * Hover and active states live in a `<style>` block rather than in inline props, because `:hover`
 * has no inline equivalent and tracking it in React state would mean a re-render per pointer move.
 *
 * The tabs are built from what the instance actually has rather than hidden with CSS — a menu entry
 * that leads somewhere refusing to answer is worse than no entry at all.
 */
export default function AppShell({
  spend,
  onAskForBudget,
  onDismissBudgetDecision,
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

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--canvas)', color: 'var(--text)', boxSizing: 'border-box' }}>
      <style>{`
        .ss-nav-item {
          position: relative;
          display: flex;
          align-items: center;
          gap: 11px;
          height: 36px;
          padding: 0 10px;
          border: none;
          border-radius: 9px;
          background: transparent;
          color: var(--text-dim);
          font-family: inherit;
          font-size: 13.5px;
          font-weight: 500;
          text-align: left;
          cursor: pointer;
          overflow: hidden;
          white-space: nowrap;
          transition: background 0.13s ease, color 0.13s ease;
        }
        .ss-nav-item:hover { background: var(--surface-2); color: var(--text); }
        .ss-nav-item[data-active="true"] {
          background: var(--accent-soft);
          color: var(--accent-text);
          font-weight: 600;
        }
        /* A short bar rather than a full-height edge: it marks the row without boxing it in. */
        .ss-nav-item[data-active="true"]::before {
          content: "";
          position: absolute;
          left: 0;
          top: 8px;
          bottom: 8px;
          width: 2.5px;
          border-radius: 0 2px 2px 0;
          background: var(--accent);
        }
        .ss-nav-icon {
          width: 18px;
          height: 18px;
          flex-shrink: 0;
          display: flex;
          align-items: center;
          justify-content: center;
        }
      `}</style>

      {/* Header */}
      <div style={{ flexShrink: 0, height: 54, display: 'flex', alignItems: 'center', gap: 12, padding: '0 28px', background: 'var(--surface)', borderBottom: '1px solid var(--border)', zIndex: 2 }}>
        <div style={{ width: 22, height: 22, borderRadius: 6, background: 'var(--accent)', flexShrink: 0 }} />
        <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>SheetSmith</span>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: 'var(--text-faint)', display: 'flex', alignItems: 'center', gap: 5 }}>
          <span style={{ color: 'var(--accent)' }}>⟡</span> {providerMode === 'CLOUD' ? 'Cloud mode' : 'Local mode'}
        </span>

        {/* Here rather than on one screen, because a ceiling is only useful while you are working
            towards it — finding out you are at 95% by visiting a page you had no reason to open is
            finding out too late. Absent entirely where there is no limit: a gauge with no maximum
            is furniture. */}
        {spend?.monthlyBudget != null && (
          <span style={{ marginLeft: 18, marginRight: 4, display: 'flex', alignItems: 'center', gap: 10 }}
                title="Your spend this calendar month">
            <BudgetBar spent={spend.spentThisMonth} limit={spend.monthlyBudget} compact />

            {/* Only in the last stretch of the limit, and absent rather than disabled below it: a
                control that refuses is one people stop pressing. Once asked it says so instead of
                offering a second request nobody would answer twice. */}
            {spend.pending ? (
              <span style={{ fontSize: 11.5, color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>
                request sent
              </span>
            ) : spend.mayAsk && (
              <Button size="sm" onClick={onAskForBudget}>Ask for more</Button>
            )}
          </span>
        )}
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
      <div style={{ flexShrink: 0 }}>
        <DefaultPasswordNotice user={user} onOpenSettings={onOpenSettings} />
        <BudgetDecisionNotice decision={spend?.decision} onDismiss={onDismissBudgetDecision} />
      </div>

      {/* min-height: 0 is what lets the panes below scroll instead of stretching the row. */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>

        <nav style={{
          width: expanded ? 190 : 58,
          flexShrink: 0,
          borderRight: '1px solid var(--border)',
          background: 'var(--surface)',
          transition: 'width 0.16s ease',
          display: 'flex',
          flexDirection: 'column',
          padding: '12px 9px',
          gap: 3,
          overflow: 'hidden',
        }}>
          {tabs.map(tab => (
            <button
              key={tab.route}
              className="ss-nav-item"
              data-active={tab.route === route}
              onClick={() => onNavigate(tab.route)}
              title={expanded ? undefined : tab.label}
              /* Named in both states: collapsed there is no text beside the icon, and an icon
                 cannot be read aloud. */
              aria-label={tab.label}
            >
              <span className="ss-nav-icon">{tab.icon}</span>
              {expanded && tab.label}
            </button>
          ))}

          <div style={{ flex: 1 }} />

          <div style={{ height: 1, background: 'var(--border)', margin: '6px 2px 7px' }} />

          {/* Absent rather than disabled for the same reason the Users tab is: an entry that leads
              somewhere refusing to answer is worse than no entry at all. */}
          {onOpenSettings && (
            <button
              className="ss-nav-item"
              onClick={onOpenSettings}
              title={expanded ? undefined : 'Settings'}
              aria-label="Settings"
            >
              <span className="ss-nav-icon"><SettingsIcon /></span>
              {expanded && 'Settings'}
            </button>
          )}

          {/* Always in view, because the frame's own control must not be hidden by the contents. */}
          <button
            className="ss-nav-item"
            onClick={toggleNav}
            title={expanded ? 'Collapse the menu' : 'Expand the menu'}
            aria-label={expanded ? 'Collapse the menu' : 'Expand the menu'}
            style={{ height: 32, color: 'var(--text-faint)', fontSize: 12.5 }}
          >
            <span className="ss-nav-icon"><ChevronIcon pointing={expanded ? 'left' : 'right'} /></span>
            {expanded && 'Collapse'}
          </button>
        </nav>

        {/* The content pane is what scrolls now, so the frame around it stays where it is. */}
        <div style={{ flex: 1, minWidth: 0, overflowY: 'auto', overflowX: 'hidden' }}>
          {children}
        </div>
      </div>
    </div>
  );
}
