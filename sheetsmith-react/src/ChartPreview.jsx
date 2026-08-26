import { useState } from 'react';

const mono = "'JetBrains Mono', monospace";

// Colors that work on both light and dark backgrounds
const PALETTE = [
  ['#22a06b', '#16a34a'],
  ['#3b82f6', '#2563eb'],
  ['#f97316', '#ea580c'],
  ['#eab308', '#ca8a04'],
  ['#a855f7', '#9333ea'],
  ['#ec4899', '#db2777'],
  ['#14b8a6', '#0d9488'],
  ['#f43f5e', '#e11d48'],
];

const W = 600, H = 210;
const PL = 56, PR = 16, PT = 28, PB = 38;
const CHART_W = W - PL - PR;
const CHART_H = H - PT - PB;

const fmt = (v) => v >= 1000 ? `$${(v / 1000).toFixed(0)}k` : String(Math.round(v * 100) / 100);
const color = (i, hot) => PALETTE[i % PALETTE.length][hot ? 1 : 0];

/**
 * `type` is the chart's real kind as the file stores it — a pie in the workbook has to come out a
 * pie here, or the preview is just a confident guess. `note` is for when it IS a guess.
 */
export default function ChartPreview({ data, type = 'bar', title, note, theme: _theme }) {
  const { xLabels, series } = data;

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)', padding: '20px 20px 14px', overflow: 'hidden' }}>
      {title && (
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 14 }}>{title}</div>
      )}

      {type === 'pie'
        ? <PieChart xLabels={xLabels} series={series} />
        : <AxisChart xLabels={xLabels} series={series} line={type === 'line'} />}

      {note && (
        <div style={{ fontFamily: mono, fontSize: 10.5, color: 'var(--text-faint)', marginTop: 10 }}>{note}</div>
      )}
    </div>
  );
}

// ── Stacked bars / lines — same grid, same hover, different mark ─────────────

function AxisChart({ xLabels, series, line }) {
  const [hovered, setHovered] = useState(null); // { xi, si }

  // Lines sit side by side, bars stack — so the ceiling differs.
  const totals = xLabels.map((_, xi) =>
    line
      ? Math.max(...series.map((s) => s.values[xi] ?? 0))
      : series.reduce((sum, s) => sum + (s.values[xi] ?? 0), 0)
  );
  const maxVal = Math.max(...totals, 1) * 1.18;

  const yPos = (v) => PT + CHART_H - (v / maxVal) * CHART_H;
  const GROUP_W = CHART_W / xLabels.length;
  const BAR_W = GROUP_W * 0.5;
  const xPos = (xi) => PL + xi * GROUP_W + GROUP_W / 2;

  const yTicks = buildYTicks(maxVal);

  return (
    <>
      {/* Legend — only for multi-series (single series colors by bar, no legend needed) */}
      {series.length > 1 && <Legend items={series.map((s, si) => ({ label: s.name, si }))} />}

      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block', overflow: 'visible' }}>

        {/* Y grid */}
        {yTicks.map(v => {
          const y = yPos(v);
          return (
            <g key={v}>
              <line x1={PL} y1={y} x2={W - PR} y2={y} stroke="var(--border)" strokeWidth={1} />
              <text x={PL - 7} y={y + 4} textAnchor="end" style={{ fontSize: 9, fill: 'var(--text-faint)', fontFamily: mono }}>
                {v === 0 ? '0' : fmt(v)}
              </text>
            </g>
          );
        })}

        {/* Lines first, so the hover dots below sit on top of them */}
        {line && series.map((s, si) => (
          <polyline
            key={si}
            points={xLabels.map((_, xi) => `${xPos(xi)},${yPos(s.values[xi] ?? 0)}`).join(' ')}
            fill="none"
            stroke={color(si, false)}
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}

        {xLabels.map((label, xi) => {
          const groupX = PL + xi * GROUP_W + (GROUP_W - BAR_W) / 2;
          let stackY = PT + CHART_H;

          // Draw segments bottom-up (reversed series order)
          const segments = [...series].reverse().map((s, revIdx) => {
            const si = series.length - 1 - revIdx;
            const val = s.values[xi] ?? 0;
            const segH = (val / maxVal) * CHART_H;
            const y = stackY - segH;
            if (!line) stackY = y;
            const isH = hovered?.xi === xi && hovered?.si === si;
            return { si, val, segH, y, isH };
          });

          return (
            <g key={`${label}-${xi}`}>
              {segments.map(({ si, val, segH, y, isH }) => line ? (
                <circle
                  key={si}
                  cx={xPos(xi)}
                  cy={yPos(val)}
                  r={isH ? 5.5 : 3.5}
                  fill={color(si, isH)}
                  style={{ cursor: 'pointer', transition: 'r 0.12s, fill 0.12s' }}
                  onMouseEnter={() => setHovered({ xi, si })}
                  onMouseLeave={() => setHovered(null)}
                />
              ) : (
                <rect
                  key={si}
                  x={groupX}
                  y={y - 1}
                  width={BAR_W}
                  height={segH + 1}
                  rx={3}
                  fill={color(series.length === 1 ? xi : si, isH)}
                  style={{ cursor: 'pointer', transition: 'fill 0.12s' }}
                  onMouseEnter={() => setHovered({ xi, si })}
                  onMouseLeave={() => setHovered(null)}
                />
              ))}

              {/* Total label — a stack's height; for lines the peak would just clutter */}
              {!line && (
                <text x={groupX + BAR_W / 2} y={yPos(totals[xi]) - 7} textAnchor="middle" style={{ fontSize: 10, fill: 'var(--text-dim)', fontFamily: mono, fontWeight: 600 }}>
                  {fmt(totals[xi])}
                </text>
              )}

              {/* X label */}
              <text x={groupX + BAR_W / 2} y={H - 10} textAnchor="middle" style={{ fontSize: 11, fill: 'var(--text-dim)', fontFamily: mono, fontWeight: 600 }}>
                {label.length > 8 ? label.slice(0, 7) + '…' : label}
              </text>
            </g>
          );
        })}

        {/* Hover tooltip */}
        {hovered && (() => {
          const { xi, si } = hovered;
          const val = series[si].values[xi] ?? 0;
          const tx = xPos(xi);
          const ty = yPos(line ? val : totals[xi]) - 64;
          return (
            <g style={{ pointerEvents: 'none' }}>
              <rect x={tx - 56} y={ty} width={112} height={42} rx={8} style={{ fill: 'var(--surface)', stroke: 'var(--border-strong)', strokeWidth: 1 }} />
              <text x={tx} y={ty + 15} textAnchor="middle" style={{ fontSize: 10, fill: 'var(--text-faint)', fontFamily: mono }}>{series[si].name}</text>
              <text x={tx} y={ty + 31} textAnchor="middle" style={{ fontSize: 12, fill: 'var(--text)', fontFamily: mono, fontWeight: 700 }}>{fmt(val)}</text>
            </g>
          );
        })()}
      </svg>
    </>
  );
}

// ── Pie ──────────────────────────────────────────────────────────────────────

const CX = W / 2, CY = PT + CHART_H / 2, R = 78;

function PieChart({ xLabels, series }) {
  const [hovered, setHovered] = useState(null); // slice index

  // A pie plots one series; a workbook that stacks several into one still only shows the first.
  const values = (series[0]?.values ?? []).map((v) => (v > 0 ? v : 0));
  const total = values.reduce((sum, v) => sum + v, 0);

  if (total <= 0) {
    return (
      <div style={{ fontFamily: mono, fontSize: 11.5, color: 'var(--text-faint)', padding: '28px 0', textAlign: 'center' }}>
        This chart’s series has no positive values to plot.
      </div>
    );
  }

  let angle = -Math.PI / 2;
  const slices = values.map((v, i) => {
    const sweep = (v / total) * Math.PI * 2;
    const slice = { i, value: v, from: angle, to: angle + sweep, label: xLabels[i] ?? `#${i + 1}` };
    // A running total inside a pure map over the slices — the same input gives the same output, and
    // it is never read after this render. State would be strictly worse here.
    // eslint-disable-next-line react-hooks/immutability
    angle += sweep;
    return slice;
  }).filter((s) => s.value > 0);

  const active = hovered != null ? slices.find((s) => s.i === hovered) : null;

  return (
    <>
      <Legend items={slices.map((s) => ({ label: s.label, si: s.i }))} />

      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block', overflow: 'visible' }}>
        {slices.length === 1 ? (
          <circle
            cx={CX} cy={CY} r={R}
            fill={color(slices[0].i, hovered != null)}
            style={{ cursor: 'pointer', transition: 'fill 0.12s' }}
            onMouseEnter={() => setHovered(slices[0].i)}
            onMouseLeave={() => setHovered(null)}
          />
        ) : slices.map((s) => (
          <path
            key={s.i}
            d={slicePath(s.from, s.to, hovered === s.i ? R + 5 : R)}
            fill={color(s.i, hovered === s.i)}
            stroke="var(--surface)"
            strokeWidth={1.5}
            style={{ cursor: 'pointer', transition: 'fill 0.12s, d 0.12s' }}
            onMouseEnter={() => setHovered(s.i)}
            onMouseLeave={() => setHovered(null)}
          />
        ))}

        {/* Hover readout — the slice is already highlighted, so this sits clear of the pie */}
        {active && (
          <g style={{ pointerEvents: 'none' }}>
            <text x={CX} y={PT - 10} textAnchor="middle" style={{ fontSize: 11, fill: 'var(--text-dim)', fontFamily: mono, fontWeight: 600 }}>
              {active.label} · {fmt(active.value)} · {((active.value / total) * 100).toFixed(1)}%
            </text>
          </g>
        )}
      </svg>
    </>
  );
}

function slicePath(from, to, r) {
  const x1 = CX + r * Math.cos(from), y1 = CY + r * Math.sin(from);
  const x2 = CX + r * Math.cos(to), y2 = CY + r * Math.sin(to);
  const large = to - from > Math.PI ? 1 : 0;
  return `M ${CX} ${CY} L ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} Z`;
}

// ── Shared ───────────────────────────────────────────────────────────────────

function Legend({ items }) {
  return (
    <div style={{ display: 'flex', gap: 18, marginBottom: 18, flexWrap: 'wrap' }}>
      {items.map(({ label, si }) => (
        <div key={si} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 10, height: 10, borderRadius: 3, background: color(si, false), flexShrink: 0 }} />
          <span style={{ fontFamily: mono, fontSize: 11, color: 'var(--text-dim)' }}>{label}</span>
        </div>
      ))}
    </div>
  );
}

function buildYTicks(maxVal) {
  const steps = [1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000];
  const step = steps.find(s => maxVal / s <= 6) ?? 500000;
  const ticks = [];
  for (let v = 0; v <= maxVal; v += step) ticks.push(v);
  return ticks;
}
