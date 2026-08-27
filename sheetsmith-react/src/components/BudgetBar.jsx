const mono = "'JetBrains Mono', monospace";

/**
 * The same rule the analytics screen uses: more places below a dollar.
 *
 * Two decimals alone produced "$0.00 / $0.05 · 7%", where the percentage is right and the money it
 * is a percentage of reads as nothing. Small sums are the normal case on an instance that mostly
 * runs locally, so this is the common reading rather than an edge one.
 */
const money = (value) => {
  const n = Number(value ?? 0);
  return `$${n.toFixed(n > 0 && n < 1 ? 4 : 2)}`;
};

/**
 * How much of a monthly limit has gone.
 *
 * A bar rather than two numbers, because the question people actually have is "how close am I",
 * and a proportion answers that before either figure has been read. The numbers stay alongside it:
 * a bar on its own cannot say whether eighty per cent is of five dollars or five hundred.
 *
 * Colour carries the same three states everything else here uses, and never carries them alone —
 * the percentage is written out, so this is readable without seeing colour at all.
 *
 * Over the limit the fill stops at full and the figure keeps counting: a bar that grew past its own
 * track would be drawing a proportion of nothing, and 140% is the fact worth reading anyway.
 */
export default function BudgetBar({ spent, limit, compact = false }) {
  // Both states occupy the same box. The column is right-aligned, so a bare run of text ended at
  // the cell's edge while the gauge beside it started a fixed width further left — the two rows
  // did not line up, and a column that does not line up reads as a mistake even when the numbers
  // are right.
  const box = { display: 'inline-block', width: compact ? 150 : 200, textAlign: 'left' };

  // No ceiling is a real state, not a missing one.
  //
  // The figure appears only where it was actually measured. The accounts list does not work out
  // spend for somebody with no limit — there is nothing to compare it against and it would be a
  // query per row — so `spent` arrives null there, and printing money(null) rendered "$0.00 · no
  // limit" beside an account that had plainly spent something.
  if (limit == null) {
    return (
      <span style={{ ...box, fontFamily: mono, fontSize: compact ? 12 : 12.5, color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>
        {spent == null ? 'no limit' : `${money(spent)} · no limit`}
      </span>
    );
  }

  const used = Number(spent ?? 0);
  const ceiling = Number(limit);
  // A limit of zero means nothing may be spent, and anything at all is past it. Dividing by it
  // would answer Infinity, which is not a percentage anybody wants rendered.
  const percent = ceiling > 0 ? Math.round((used / ceiling) * 100) : (used > 0 ? 100 : 0);

  const tone = percent >= 100 ? 'var(--del)' : percent >= 80 ? 'var(--warn)' : 'var(--accent)';

  return (
    // The box is a fixed width rather than a minimum. With a minimum the track stretched to
    // whatever the figures above it happened to measure, so a limit written as 22.00 drew a
    // shorter bar than one written as 0.0500 — two gauges of different lengths, which is the one
    // thing a gauge must not be.
    <span style={box}>
      <span style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 4 }}>
        <span style={{ fontFamily: mono, fontSize: compact ? 11.5 : 12.5, color: 'var(--text)' }}>
          {money(used)}
        </span>
        <span style={{ fontFamily: mono, fontSize: compact ? 11 : 12, color: 'var(--text-faint)' }}>
          / {money(ceiling)}
        </span>
        <span style={{ fontFamily: mono, fontSize: compact ? 11 : 12, color: tone, marginLeft: 'auto' }}>
          {percent}%
        </span>
      </span>

      <span
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${percent}% of the monthly spend limit used`}
        style={{ display: 'block', height: 5, borderRadius: 3, background: 'var(--surface-2)', overflow: 'hidden' }}
      >
        <span style={{
          display: 'block',
          width: `${Math.min(100, percent)}%`,
          height: '100%',
          borderRadius: 3,
          background: tone,
          transition: 'width 0.2s',
        }} />
      </span>
    </span>
  );
}
