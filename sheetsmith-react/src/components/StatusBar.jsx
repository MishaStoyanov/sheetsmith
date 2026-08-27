const mono = "'JetBrains Mono', monospace";

/**
 * Status colours, reserved. These four never stand in for "series 3" — a chart that borrows the
 * failure red for a provider teaches people to read red as a provider, which is a lesson that then
 * has to be unlearned on the screen where it means failure.
 */
const STATUS = {
  COMPLETED: { colour: 'var(--accent)', label: 'Completed' },
  PARTIAL: { colour: 'var(--warn)', label: 'Partial' },
  FAILED: { colour: 'var(--del)', label: 'Failed' },
  PROCESSING: { colour: 'var(--text-faint)', label: 'Still running' },
};

const ORDER = ['COMPLETED', 'PARTIAL', 'FAILED', 'PROCESSING'];

/**
 * How a set of runs ended, as one bar of the whole.
 *
 * A single stacked bar rather than a ring: there are at most four states, they have a natural order
 * from best to worst, and a bar keeps that order readable where a ring makes it a matter of where
 * you start counting. Every segment is named beneath it, never colour alone.
 */
export default function StatusBar({ counts }) {
  const known = ORDER
    .map(status => ({ status, count: counts.find(row => row.label === status)?.count ?? 0 }))
    .filter(row => row.count > 0);

  const total = known.reduce((sum, row) => sum + row.count, 0);
  if (!total) {
    return <div style={{ fontSize: 13, color: 'var(--text-faint)' }}>No runs in this range.</div>;
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 2, height: 10, marginBottom: 12 }}>
        {known.map(row => (
          <span
            key={row.status}
            style={{
              width: `${(row.count / total) * 100}%`,
              background: STATUS[row.status].colour,
              borderRadius: 3,
            }}
          />
        ))}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 18px' }}>
        {known.map(row => (
          <span key={row.status} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--text-dim)' }}>
            <span style={{ width: 9, height: 9, borderRadius: 3, background: STATUS[row.status].colour, flexShrink: 0 }} />
            {STATUS[row.status].label}
            <span style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--text)' }}>{row.count.toLocaleString()}</span>
          </span>
        ))}
      </div>
    </div>
  );
}
