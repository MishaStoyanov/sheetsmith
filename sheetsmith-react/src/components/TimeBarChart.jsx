import { useCallback, useState } from 'react';
import { Panel } from './DonutChart.jsx';
import { palette } from './seriesColors.js';

const mono = "'JetBrains Mono', monospace";

const H = 190;
const PAD_TOP = 14;
const PAD_BOTTOM = 26;
const PAD_LEFT = 46;

/**
 * One measure over time.
 *
 * One axis, one series — never a second scale for money beside tokens. Those are two measures of
 * different size, and putting them on one plot with two axes lets the eye read a relationship that
 * the numbers do not contain. The toggle above switches which one is being shown instead.
 *
 * The bars carry a hover tooltip because an SVG chart in a browser is interactive whether or not
 * anybody planned it, and a bar with no way to read its value is a bar that has to be estimated
 * against a gridline.
 */
export default function TimeBarChart({ title, buckets, format, theme, action, empty = 'Nothing to show yet' }) {
  const [hovered, setHovered] = useState(null);

  // The chart is drawn at the width it is actually given rather than scaled into place by a
  // viewBox. Stretching a viewBox to fill the panel would stretch the labels with it, and
  // horizontally squashed monospace type is unmistakably wrong even when nothing else is.
  //
  // A callback ref rather than useRef with an effect: on the first render there is no data yet and
  // this component returns the empty panel, so the element does not exist — an effect with no
  // dependencies would attach its observer to null and never look again, which is exactly what it
  // did.
  const [measured, setMeasured] = useState(720);
  const frame = useCallback(element => {
    if (!element || typeof ResizeObserver === 'undefined') return;
    setMeasured(element.getBoundingClientRect().width);
    new ResizeObserver(([entry]) => setMeasured(entry.contentRect.width)).observe(element);
  }, []);

  if (!buckets.length) {
    return (
      <Panel title={title} action={action}>
        <div style={{ padding: '58px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-faint)' }}>
          {empty}
        </div>
      </Panel>
    );
  }

  const colour = palette(theme)[0];
  // Floored at 1 only when everything is zero. Flooring unconditionally is what flattened the
  // money view: costs are fractions of a currency unit, so a floor of 1 made every bar a
  // sliver against a scale that went to a dollar nobody had spent.
  const largest = Math.max(...buckets.map(b => b.value));
  const max = largest > 0 ? largest : 1;

  // Fills the panel, unless there are so many buckets that the bars would be threads — past
  // that the chart keeps its own size and the container scrolls.
  const needed = buckets.length * 46;
  const crowded = needed > measured;
  const width = crowded ? needed : measured;
  const plotWidth = width - PAD_LEFT - 12;
  const plotHeight = H - PAD_TOP - PAD_BOTTOM;
  const slot = plotWidth / buckets.length;
  const barWidth = Math.min(46, slot * 0.6);

  const y = value => PAD_TOP + plotHeight - (value / max) * plotHeight;

  // Three lines, not a grid: the axis is there to be referred to, not read.
  const ticks = [0, max / 2, max];

  return (
    <Panel title={title} action={action}>
      <div ref={frame} style={{ overflowX: 'auto' }}>
        <svg width={width} height={H} style={{ display: 'block', overflow: 'visible' }}>
          {ticks.map(tick => (
            <g key={tick}>
              <line x1={PAD_LEFT} y1={y(tick)} x2={width - 12} y2={y(tick)} stroke="var(--border)" strokeWidth={1} />
              <text x={PAD_LEFT - 8} y={y(tick) + 3.5} textAnchor="end" style={{ fontFamily: mono, fontSize: 9, fill: 'var(--text-faint)' }}>
                {format(tick, true)}
              </text>
            </g>
          ))}

          {buckets.map((bucket, i) => {
            const x = PAD_LEFT + i * slot + (slot - barWidth) / 2;
            const top = y(bucket.value);
            const height = Math.max(2, PAD_TOP + plotHeight - top);
            return (
              <g key={bucket.label} onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)}>
                {/* A transparent full-height target, so the bar does not have to be hit exactly. */}
                <rect x={PAD_LEFT + i * slot} y={PAD_TOP} width={slot} height={plotHeight} fill="transparent" />
                <rect
                  x={x} y={top} width={barWidth} height={height}
                  rx={4}
                  fill={colour}
                  opacity={hovered == null || hovered === i ? 1 : 0.4}
                  style={{ transition: 'opacity 0.12s' }}
                />
                <text x={x + barWidth / 2} y={H - 9} textAnchor="middle" style={{ fontFamily: mono, fontSize: 9, fill: 'var(--text-faint)' }}>
                  {bucket.label.slice(5)}
                </text>
              </g>
            );
          })}

          {hovered != null && (
            <g style={{ pointerEvents: 'none' }}>
              {(() => {
                const bucket = buckets[hovered];
                const cx = PAD_LEFT + hovered * slot + slot / 2;
                const boxWidth = 116;
                const bx = Math.min(Math.max(cx - boxWidth / 2, 2), width - boxWidth - 2);
                const by = Math.max(y(bucket.value) - 48, 2);
                return (
                  <>
                    <rect x={bx} y={by} width={boxWidth} height={40} rx={8}
                          fill="var(--surface)" stroke="var(--border-strong)" strokeWidth={1} />
                    <text x={bx + boxWidth / 2} y={by + 16} textAnchor="middle" style={{ fontFamily: mono, fontSize: 9.5, fill: 'var(--text-faint)' }}>
                      {bucket.label}
                    </text>
                    <text x={bx + boxWidth / 2} y={by + 31} textAnchor="middle" style={{ fontFamily: mono, fontSize: 12, fontWeight: 600, fill: 'var(--text)' }}>
                      {format(bucket.value)}
                    </text>
                  </>
                );
              })()}
            </g>
          )}
        </svg>
      </div>
    </Panel>
  );
}
