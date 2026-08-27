const mono = "'JetBrains Mono', monospace";

const money = value => `$${Number(value ?? 0).toFixed(Number(value) > 0 && Number(value) < 1 ? 4 : 2)}`;

/**
 * The answer to a request for a bigger ceiling, shown once.
 *
 * Both outcomes appear here, and that is the point: a refusal nobody is ever told about is a
 * request that simply vanished, which teaches people the button does nothing. A yes and a no are
 * equally worth a sentence — they differ in colour and wording, not in whether they arrive.
 *
 * Dismissing is what marks it read, on the server rather than in this browser: somebody who opens
 * the app on a second machine has already been told.
 */
export default function BudgetDecisionNotice({ decision, onDismiss }) {
  if (!decision) return null;

  const approved = decision.status === 'APPROVED';

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: '9px 28px',
      background: approved ? 'var(--accent-soft)' : 'var(--warn-bg)',
      borderBottom: '1px solid var(--border)',
      fontSize: 13.5,
      color: approved ? 'var(--accent-text)' : 'var(--warn)',
    }}>
      <span style={{ flex: 1, minWidth: 0 }}>
        {approved ? (
          <>
            Your spend limit was raised to{' '}
            <span style={{ fontFamily: mono }}>{money(decision.newLimit)}</span> for this month.
          </>
        ) : (
          <>
            Your request for a higher spend limit was declined. The limit is unchanged — talk to
            whoever runs this instance if you need more.
          </>
        )}
      </span>

      <button
        onClick={onDismiss}
        style={{
          background: 'none', border: 'none', padding: 0,
          font: 'inherit', fontSize: 13, cursor: 'pointer',
          color: 'inherit', textDecoration: 'underline', textUnderlineOffset: 3, flexShrink: 0,
        }}
      >
        Dismiss
      </button>
    </div>
  );
}
