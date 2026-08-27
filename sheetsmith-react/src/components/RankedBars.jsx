import { Panel } from './DonutChart.jsx';

const mono = "'JetBrains Mono', monospace";

/**
 * A ranked list of one measure — the right form when the question is "which of these is biggest",
 * the labels are words rather than dates, and there are more of them than a ring can hold.
 *
 * Bars run along the row so the labels can be read horizontally at their natural length; a vertical
 * chart of eight action names would either rotate the type or truncate it. The number is written at
 * the end of every row rather than left to be measured against an axis, because with fewer than ten
 * rows the axis costs more than it explains.
 *
 * One colour throughout: these are ranks of the same thing, not different series, and giving each
 * row its own hue would imply an identity the data does not have.
 */
export default function RankedBars({ title, rows, colour = 'var(--accent)', format = value => value.toLocaleString(), empty = 'Nothing yet' }) {
  if (!rows.length) {
    return (
      <Panel title={title}>
        <div style={{ padding: '34px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-faint)' }}>
          {empty}
        </div>
      </Panel>
    );
  }

  const largest = Math.max(...rows.map(row => row.count));
  const max = largest > 0 ? largest : 1;

  return (
    <Panel title={title}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
        {rows.map(row => (
          <div key={row.label} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              title={row.label}
              style={{
                width: '38%', flexShrink: 0, fontSize: 12.5, color: 'var(--text-dim)',
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              }}
            >
              {row.label}
            </span>
            <span style={{ flex: 1, height: 8, borderRadius: 4, background: 'var(--surface-2)', overflow: 'hidden' }}>
              <span style={{ display: 'block', width: `${(row.count / max) * 100}%`, height: '100%', borderRadius: 4, background: colour }} />
            </span>
            {/* Text wears text tokens; the bar beside it is what carries the colour. */}
            <span style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--text)', width: 48, textAlign: 'right' }}>
              {format(row.count)}
            </span>
          </div>
        ))}
      </div>
    </Panel>
  );
}
