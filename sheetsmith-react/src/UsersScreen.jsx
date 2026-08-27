import { useCallback, useEffect, useState } from 'react';
import Badge from './components/Badge.jsx';
import Button from './components/Button.jsx';
import DataTable from './components/DataTable.jsx';
import Field from './components/Field.jsx';
import Modal from './components/Modal.jsx';
import Pagination from './components/Pagination.jsx';
import { changeUserRole, createUser, deleteUser, searchUsers, setUserBudget, updateUser } from './settingsApi.js';

const mono = "'JetBrains Mono', monospace";

/** How a role reads to a person, rather than how it is stored. */
const ROLE_LABEL = {
  USER: 'user',
  ADMIN: 'admin',
  SUPERADMIN: 'superadmin',
};

/**
 * Accounts on this instance.
 *
 * Only administrators get here at all, and what they see depends on which kind they are: handing
 * out access is open to any of them, taking it back is not. The screen shows that difference by
 * offering the button or not — but the rule itself lives on the server, and a request that skips
 * this screen meets it just the same.
 */
export default function UsersScreen({ currentUser, onSelfRenamed }) {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState(null);
  const [repassword, setRepassword] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [roleChange, setRoleChange] = useState(null);
  const [budgetFor, setBudgetFor] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return searchUsers(keyword || null, page)
      .then(result => { setData(result); setError(null); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [keyword, page]);

  useEffect(() => {
    // The fetch flips a loading flag on its way out, which the rule reads as a cascading render.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const after = async (work) => {
    try {
      await work();
      setError(null);
      await load();
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  };

  const iAmSuperadmin = currentUser?.role === 'SUPERADMIN';

  const columns = [
    {
      key: 'name',
      header: 'Name',
      render: user => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontFamily: mono, fontSize: 12.5 }}>{user.name}</span>
          {user.id === currentUser?.id && <Badge tone="good">you</Badge>}
          {/* Named as what it is rather than as "admin": the account can be renamed. */}
          {user.protectedAccount && <Badge>default account</Badge>}
          {user.mustChangePassword && <Badge tone="warn">default password</Badge>}
        </span>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      render: user => (
        <Badge tone={user.role === 'USER' ? 'neutral' : 'good'}>{ROLE_LABEL[user.role] ?? user.role}</Badge>
      ),
    },
    {
      key: 'budget',
      header: 'This month',
      align: 'right',
      render: user => {
        if (user.monthlyBudget == null) {
          return <span style={{ fontSize: 12.5, color: 'var(--text-faint)' }}>no limit</span>;
        }
        // Spent and allowed together. A ceiling on its own is a number nobody can act on, and the
        // question anybody actually has is how close somebody is to it.
        const spent = Number(user.spentThisMonth ?? 0);
        const limit = Number(user.monthlyBudget);
        const spentUp = limit > 0 && spent >= limit;
        return (
          <span style={{ fontFamily: mono, fontSize: 12, whiteSpace: 'nowrap', color: spentUp ? 'var(--del)' : 'var(--text-dim)' }}>
            ${spent.toFixed(2)} / ${limit.toFixed(2)}
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: user => (
        <span style={{ display: 'inline-flex', gap: 6, whiteSpace: 'nowrap' }}>
          {/*
            The one-way door, drawn. Any administrator may hand out access; only the seeded account
            can take it back, so everyone else simply is not offered the second button. Your own row
            has neither — a role you can change yourself is a role that means nothing.
          */}
          {user.role === 'USER' && user.id !== currentUser?.id && (
            <Button size="sm" variant="ghost" onClick={() => setRoleChange({ user, to: 'ADMIN' })}>
              Make admin
            </Button>
          )}
          {user.role === 'ADMIN' && user.id !== currentUser?.id && iAmSuperadmin && (
            <Button size="sm" variant="ghost" onClick={() => setRoleChange({ user, to: 'USER' })}>
              Remove admin
            </Button>
          )}
          {/* Beside the other things about this account rather than on a screen of its own: a
              spend limit is a property of a person, like their name. Not on your own row, for the
              same reason as the role — a limit you can lift is not a limit. */}
          {user.id !== currentUser?.id && (
            <Button size="sm" variant="ghost" onClick={() => setBudgetFor(user)}>Limit</Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => setRenaming({ id: user.id, name: user.name })}>
            Rename
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setRepassword({ id: user.id, name: user.name })}>
            Password
          </Button>
          {/* The two rules that stop an instance locking itself out are shown, not just enforced:
              a button that always refuses is worse than no button. */}
          {!user.protectedAccount && user.id !== currentUser?.id && (
            <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(user)} style={{ color: 'var(--del)' }}>
              Delete
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div style={{ maxWidth: 1180, margin: '0 auto', padding: '40px 28px 100px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, marginBottom: 6 }}>
        <div style={{ flex: 1 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 6px' }}>Users</h1>
          <p style={{ fontSize: 14, color: 'var(--text-dim)', margin: 0 }}>
            Everyone who can sign in. Admins manage accounts; everybody else just uses the app.
          </p>
        </div>
        <Button variant="primary" onClick={() => setCreating(true)}>Add user</Button>
      </div>

      <div style={{ margin: '22px 0 18px' }}>
        <input
          value={keyword}
          onChange={e => { setKeyword(e.target.value); setPage(0); }}
          placeholder="Search by name"
          style={{ height: 34, width: 240, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'var(--surface-2)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
        />
      </div>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5, marginBottom: 18 }}>
          {error}
        </div>
      )}

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        loading={loading}
        filtered={!!keyword}
        empty={{ icon: '◍', title: 'No accounts', hint: 'Add one to let another person in.' }}
        emptyFiltered={{ icon: '◍', title: 'Nobody by that name', hint: 'Try a shorter search.' }}
      />

      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />

      <CreateDialog
        open={creating}
        onClose={() => setCreating(false)}
        onSubmit={(name, password) => after(() => createUser(name, password))}
      />

      <RenameDialog
        target={renaming}
        onClose={() => setRenaming(null)}
        onSubmit={async (name) => {
          const ok = await after(() => updateUser(renaming.id, { name }));
          if (ok && renaming.id === currentUser?.id) onSelfRenamed?.(name);
          return ok;
        }}
      />

      <PasswordDialog
        target={repassword}
        isSelf={repassword?.id === currentUser?.id}
        onClose={() => setRepassword(null)}
        onSubmit={(password, currentPassword) =>
          after(() => updateUser(repassword.id, { password, currentPassword }))}
      />

      <BudgetDialog
        key={budgetFor?.id ?? 'closed'}
        target={budgetFor}
        onClose={() => setBudgetFor(null)}
        onSubmit={value => after(() => setUserBudget(budgetFor.id, value))}
      />

      <Modal
        open={!!roleChange}
        title={roleChange?.to === 'ADMIN' ? `Make ${roleChange?.user.name} an admin?` : `Remove ${roleChange?.user.name}'s admin?`}
        onClose={() => setRoleChange(null)}
        footer={
          <>
            <Button onClick={() => setRoleChange(null)}>Cancel</Button>
            <Button
              variant="primary"
              onClick={async () => {
                if (await after(() => changeUserRole(roleChange.user.id, roleChange.to))) setRoleChange(null);
              }}
            >
              {roleChange?.to === 'ADMIN' ? 'Make admin' : 'Remove admin'}
            </Button>
          </>
        }
      >
        <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: 0 }}>
          {roleChange?.to === 'ADMIN' ? (
            <>
              They will be able to add, rename and remove accounts, and to make other people admins
              too. <strong>You will not be able to undo this</strong> — only the default account can
              take admin back, which is what stops two admins removing each other.
            </>
          ) : (
            <>
              They keep their account and everything they have done; they simply stop being able to
              manage other people.
            </>
          )}
        </p>
      </Modal>

      <Modal
        open={!!confirmDelete}
        title={`Delete ${confirmDelete?.name}?`}
        onClose={() => setConfirmDelete(null)}
        footer={
          <>
            <Button onClick={() => setConfirmDelete(null)}>Cancel</Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (await after(() => deleteUser(confirmDelete.id))) setConfirmDelete(null);
              }}
            >
              Delete
            </Button>
          </>
        }
      >
        <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: 0 }}>
          They will be signed out everywhere immediately. The runs they started stay in the history
          with no owner — a record of what happened should not disappear because somebody left.
        </p>
      </Modal>
    </div>
  );
}

function CreateDialog({ open, onClose, onSubmit }) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  const reset = () => { setName(''); setPassword(''); onClose(); };

  const submit = async () => {
    setBusy(true);
    const ok = await onSubmit(name.trim(), password);
    setBusy(false);
    if (ok) reset();
  };

  return (
    <Modal
      open={open}
      title="Add user"
      onClose={reset}
      footer={
        <>
          <Button onClick={reset}>Cancel</Button>
          <Button variant="primary" disabled={busy || !name.trim() || password.length < 4} onClick={submit}>
            {busy ? 'Adding…' : 'Add'}
          </Button>
        </>
      }
    >
      <Field label="Username" value={name} onChange={e => setName(e.target.value)} monospace autoFocus />
      <Field
        label="Password"
        type="password"
        value={password}
        onChange={e => setPassword(e.target.value)}
        hint="At least four characters. They can change it once they are in."
        monospace
      />
    </Modal>
  );
}

function RenameDialog({ target, onClose, onSubmit }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  // Keyed on the target, so the field starts from the current name each time it opens without an
  // effect resetting it afterwards.
  return (
    <Modal
      key={target?.id}
      open={!!target}
      title={`Rename ${target?.name}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || !(name || target?.name || '').trim()}
            onClick={async () => {
              setBusy(true);
              const ok = await onSubmit((name || target.name).trim());
              setBusy(false);
              if (ok) onClose();
            }}
          >
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      <Field
        label="Username"
        defaultValue={target?.name}
        onChange={e => setName(e.target.value)}
        monospace
        autoFocus
      />
    </Modal>
  );
}

function PasswordDialog({ target, isSelf, onClose, onSubmit }) {
  const [password, setPassword] = useState('');
  const [current, setCurrent] = useState('');
  const [busy, setBusy] = useState(false);

  const reset = () => { setPassword(''); setCurrent(''); onClose(); };

  return (
    <Modal
      key={target?.id}
      open={!!target}
      title={isSelf ? 'Change your password' : `Set a password for ${target?.name}`}
      onClose={reset}
      footer={
        <>
          <Button onClick={reset}>Cancel</Button>
          <Button
            variant="primary"
            disabled={busy || password.length < 4 || (isSelf && !current)}
            onClick={async () => {
              setBusy(true);
              const ok = await onSubmit(password, isSelf ? current : undefined);
              setBusy(false);
              if (ok) reset();
            }}
          >
            {busy ? 'Saving…' : 'Save'}
          </Button>
        </>
      }
    >
      {/* Asked for only when it is your own account: proving you are the person sitting there is
          what stops somebody at an unlocked screen locking the owner out. Resetting somebody
          else's has no such value to supply. */}
      {isSelf && (
        <Field
          label="Current password"
          type="password"
          value={current}
          onChange={e => setCurrent(e.target.value)}
          monospace
          autoFocus
        />
      )}
      <Field
        label="New password"
        type="password"
        value={password}
        onChange={e => setPassword(e.target.value)}
        hint="At least four characters."
        monospace
        autoFocus={!isSelf}
      />
      <p style={{ fontSize: 12.5, color: 'var(--text-faint)', margin: '4px 0 0', lineHeight: 1.5 }}>
        {isSelf
          ? 'You will be signed out everywhere, including here, and can sign back in with the new password.'
          : 'They will be signed out everywhere. Changing a password is what you do when the old one may be known to somebody else, so leaving their sessions running would defeat it.'}
      </p>
    </Modal>
  );
}

/**
 * A person's monthly ceiling, or none.
 *
 * Two things are said here rather than assumed. Empty means no limit, because a blank field is
 * otherwise read as zero — which would mean the opposite of what somebody clearing it intended.
 * And the limit only sees what has a price: a local model costs nothing and an unpriced one costs
 * an unknown amount, so neither counts towards it. A limit that quietly missed half the spending
 * would be worse than none, so the dialog says what it covers.
 */
function BudgetDialog({ target, onClose, onSubmit }) {
  const [value, setValue] = useState(target?.monthlyBudget == null ? '' : String(target.monthlyBudget));
  const [busy, setBusy] = useState(false);

  const trimmed = value.trim();
  const amount = trimmed === '' ? null : Number(trimmed);
  const wellFormed = amount === null || (Number.isFinite(amount) && amount >= 0);

  const submit = async () => {
    setBusy(true);
    const ok = await onSubmit(amount);
    setBusy(false);
    if (ok) onClose();
  };

  return (
    <Modal
      open={!!target}
      title={`Spend limit for ${target?.name}`}
      onClose={onClose}
      width={440}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={busy || !wellFormed} onClick={submit}>
            {busy ? 'Saving…' : amount === null ? 'Remove the limit' : 'Save limit'}
          </Button>
        </>
      }
    >
      <Field
        label="US dollars per calendar month"
        value={value}
        onChange={e => setValue(e.target.value)}
        placeholder="Leave empty for no limit"
        inputMode="decimal"
        monospace
        hint={
          target?.monthlyBudget != null
            ? `Spent so far this month: $${Number(target.spentThisMonth ?? 0).toFixed(2)}`
            : 'They have no limit at the moment.'
        }
      />

      <p style={{ fontSize: 12.5, color: 'var(--text-faint)', lineHeight: 1.55, margin: '2px 0 0' }}>
        Counted from the price list, so it covers cloud models that have a price and nothing else —
        a model you run locally costs nothing, and one nobody has priced costs an unknown amount.
        The month is the calendar month, and a run already under way is always allowed to finish.
      </p>
    </Modal>
  );
}
