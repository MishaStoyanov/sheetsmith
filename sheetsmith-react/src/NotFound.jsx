const mono = "'JetBrains Mono', monospace";

/**
 * Where an address that means nothing ends up: an unknown route, the users tab on an instance with
 * no accounts, a link to a run that has since been deleted. All of those used to land on whatever
 * the app happened to render, which reads as a bug.
 */
export default function NotFound({ onHome }) {
  return (
    <div style={{ maxWidth: 520, margin: '0 auto', padding: '96px 28px', textAlign: 'center' }}>
      <div style={{ fontFamily: mono, fontSize: 56, fontWeight: 500, color: 'var(--text-faint)', letterSpacing: '-0.02em' }}>
        404
      </div>
      <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '14px 0 10px' }}>
        There is nothing at this address
      </h1>
      <p style={{ fontSize: 14.5, color: 'var(--text-dim)', lineHeight: 1.6, margin: '0 0 26px' }}>
        The link may be out of date, or the page may only exist on an instance set up differently
        from this one.
      </p>
      <button
        onClick={onHome}
        style={{ height: 38, padding: '0 18px', borderRadius: 9, border: 'none', background: 'var(--accent)', color: 'var(--on-accent)', fontFamily: 'inherit', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}
      >
        Back to the spreadsheet
      </button>
    </div>
  );
}
