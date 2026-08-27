import { useState } from 'react';
import { seriesColor } from './seriesColors.js';

const mono = "'JetBrains Mono', monospace";

const SIZE = 190;
const R = 78;
const THICKNESS = 26;
const C = SIZE / 2;

function arc(startFraction, endFraction) {
  const a0 = startFraction * 2 * Math.PI - Math.PI / 2;
  const a1 = endFraction * 2 * Math.PI - Math.PI / 2;
  const outer = R;
  const inner = R - THICKNESS;
  const large = endFraction - startFraction > 0.5 ? 1 : 0;

  const p = (radius, angle) => [C + radius * Math.cos(angle), C + radius * Math.sin(angle)];
  const [x0, y0] = p(outer, a0);
  const [x1, y1] = p(outer, a1);
  const [x2, y2] = p(inner, a1);
  const [x3, y3] = p(inner, a0);

  return `M ${x0} ${y0} A ${outer} ${outer} 0 ${large} 1 ${x1} ${y1}
          L ${x2} ${y2} A ${inner} ${inner} 0 ${large} 0 ${x3} ${y3} Z`;
}

/**
 * A share-of-the-whole chart, with a legend that is always present.
 *
 * A ring rather than a filled pie so the total can sit in the middle, where the question "of what?"
 * is answered without a second glance. Segments are separated by a surface-coloured stroke rather
 * than by a gap in the geometry, so adjacent slices stay distinguishable even where two colours are
 * close.
 *
 * Identity is never carried by colour alone: every slice is named in the legend beside its number.
 */
export default function DonutChart({ title, slices, format, theme, empty = 'Nothing to show yet' }) {
  const [hovered, setHovered] = useState(null);

  const keys = slices.map(s => s.label);
  const total = slices.reduce((sum, s) => sum + s.value, 0);

  if (!slices.length || total <= 0) {
    return (
      <Panel title={title}>
        <div style={{ padding: '52px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-faint)' }}>
          {empty}
        </div>
      </Panel>
    );
  }

  // The boundaries are worked out before the render rather than accumulated inside it: a
  // variable that keeps counting while JSX is produced is a variable that can carry a stale
  // total into the next render.
  const bounds = [];
  slices.reduce((offset, slice) => {
    const end = offset + slice.value / total;
    bounds.push([offset, end]);
    return end;
  }, 0);

  const shown = hovered == null ? null : slices[hovered];

  return (
    <Panel title={title}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 26, flexWrap: 'wrap' }}>
        <svg width={SIZE} height={SIZE} style={{ flexShrink: 0, overflow: 'visible' }}>
          {slices.map((slice, i) => {
            const [start, end] = bounds[i];
            return (
              <path
                key={slice.label}
                d={arc(start, end)}
                fill={seriesColor(slice.label, keys, theme)}
                stroke="var(--surface)"
                strokeWidth={2}
                opacity={hovered == null || hovered === i ? 1 : 0.35}
                onMouseEnter={() => setHovered(i)}
                onMouseLeave={() => setHovered(null)}
                style={{ transition: 'opacity 0.12s' }}
              />
            );
          })}

          <text x={C} y={C - 4} textAnchor="middle" style={{ fontFamily: mono, fontSize: 15, fontWeight: 600, fill: 'var(--text)' }}>
            {format(shown ? shown.value : total)}
          </text>
          <text x={C} y={C + 13} textAnchor="middle" style={{ fontFamily: mono, fontSize: 9.5, fill: 'var(--text-faint)' }}>
            {shown ? shown.label : 'total'}
          </text>
        </svg>

        {/* Always present for more than one series, so identity is never colour alone. */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 7, minWidth: 150, flex: 1 }}>
          {slices.map((slice, i) => (
            <div
              key={slice.label}
              onMouseEnter={() => setHovered(i)}
              onMouseLeave={() => setHovered(null)}
              style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 12.5, opacity: hovered == null || hovered === i ? 1 : 0.5, cursor: 'default' }}
            >
              <span style={{ width: 9, height: 9, borderRadius: 3, background: seriesColor(slice.label, keys, theme), flexShrink: 0 }} />
              <span style={{ flex: 1, color: 'var(--text-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {slice.label}
              </span>
              {/* Text wears text tokens, never the series colour. */}
              <span style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--text)' }}>{format(slice.value)}</span>
              <span style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-faint)', width: 38, textAlign: 'right' }}>
                {Math.round((slice.value / total) * 100)}%
              </span>
            </div>
          ))}
        </div>
      </div>
    </Panel>
  );
}

export function Panel({ title, action, children }) {
  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)', padding: '16px 18px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
        <span style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)' }}>
          {title}
        </span>
        <div style={{ flex: 1 }} />
        {action}
      </div>
      {children}
    </div>
  );
}
