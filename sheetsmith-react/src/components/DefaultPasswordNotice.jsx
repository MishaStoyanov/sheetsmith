import { useState } from 'react';
import Button from './Button.jsx';

const key = (userId) => `ss-password-nag:${userId}`;

/**
 * The nag about the seeded password, with two ways to make it go away.
 *
 * "Later" is this visit only. "Don't remind me" is remembered per person and per browser, in
 * localStorage rather than on the server: the account really does still have its default password,
 * and writing "dismissed" into the record would make the instance claim a state it is not in. What
 * is being silenced is the reminder, not the fact.
 */
export default function DefaultPasswordNotice({ user, onOpenSettings }) {
  const [dismissed, setDismissed] = useState(() => {
    try {
      return localStorage.getItem(key(user?.id)) === 'silenced';
    } catch {
      // A browser that refuses storage still gets the warning, which is the safe way to fail.
      return false;
    }
  });

  if (!user?.mustChangePassword || dismissed) return null;

  const silence = () => {
    try {
      localStorage.setItem(key(user.id), 'silenced');
    } catch {
      // Nothing to do: it closes for now and comes back next time, which is the honest fallback.
    }
    setDismissed(true);
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', padding: '9px 28px', background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 13, borderBottom: '1px solid var(--border)' }}>
      <span style={{ flex: 1, minWidth: 240 }}>
        This account still has its default password. Change it before anyone else uses this instance.
      </span>
      {onOpenSettings && (
        <Button size="sm" variant="ghost" onClick={onOpenSettings} style={{ color: 'var(--warn)' }}>
          Change it
        </Button>
      )}
      <Button size="sm" variant="ghost" onClick={() => setDismissed(true)} style={{ color: 'var(--warn)' }}>
        Later
      </Button>
      <Button size="sm" variant="ghost" onClick={silence} style={{ color: 'var(--warn)', opacity: 0.8 }}>
        Don&apos;t remind me
      </Button>
    </div>
  );
}
