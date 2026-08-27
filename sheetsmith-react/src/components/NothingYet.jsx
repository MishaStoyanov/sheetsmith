const mono = "'JetBrains Mono', monospace";

/**
 * What the analytics screen shows before there is anything to analyse.
 *
 * The alternative — the real charts with every panel saying "nothing yet" — is eight empty boxes,
 * and eight empty boxes read as a broken screen rather than as a new one. Axes with no data on them
 * are furniture pretending to be information.
 *
 * Two states, not one, and the difference matters: an instance that has never been used needs
 * telling what to do, while an instance with nothing in the chosen dates needs telling to look
 * wider. One message covering both would be wrong in whichever case it was not written for.
 */
export default function NothingYet({ variant, onClear }) {
  const firstRun = variant === 'never-used';

  return (
    <div style={{
      border: '1px dashed var(--border-strong)',
      borderRadius: 14,
      background: 'var(--surface)',
      padding: '46px 32px',
      textAlign: 'center',
    }}>
      <div style={{ fontFamily: mono, fontSize: 26, color: 'var(--text-faint)', marginBottom: 14 }}>
        {firstRun ? '◔' : '◌'}
      </div>

      <h2 style={{ fontSize: 16.5, fontWeight: 650, letterSpacing: '-0.01em', margin: '0 0 8px' }}>
        {firstRun ? 'Nothing to measure yet' : 'Nothing in this range'}
      </h2>

      <p style={{ fontSize: 13.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: '0 auto', maxWidth: 460 }}>
        {firstRun ? (
          <>
            This page fills itself in as the instance is used. Improve a spreadsheet or ask the
            assistant something, and the run will be counted here — who asked, which model answered,
            how many tokens it took and how long it ran.
          </>
        ) : (
          <>
            There are records here, just none between these dates. Widen the range or clear it to see
            everything.
          </>
        )}
      </p>

      {firstRun ? (
        <div style={{ marginTop: 22, display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
          <a href="#/improve" style={link(true)}>Improve a spreadsheet</a>
          {/* Offered here because it is the one piece of setup this screen actually depends on:
              without prices it can count tokens but can never answer in money. */}
          <a href="#/prices" style={link(false)}>Add model prices</a>
        </div>
      ) : (
        onClear && (
          <div style={{ marginTop: 22 }}>
            <button type="button" onClick={onClear} style={link(false)}>Clear the dates</button>
          </div>
        )
      )}
    </div>
  );
}

function link(primary) {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    height: 34,
    padding: '0 15px',
    borderRadius: 9,
    fontFamily: 'inherit',
    fontSize: 13,
    fontWeight: 500,
    textDecoration: 'none',
    cursor: 'pointer',
    border: primary ? '1px solid transparent' : '1px solid var(--border-strong)',
    background: primary ? 'var(--accent)' : 'transparent',
    color: primary ? 'var(--on-accent)' : 'var(--text-dim)',
  };
}
