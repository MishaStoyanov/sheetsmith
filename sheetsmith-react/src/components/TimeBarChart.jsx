import { useCallback, useState } from 'react';
import { Panel } from './DonutChart.jsx';
import { palette, seriesColor } from './seriesColors.js';

const mono = "'JetBrains Mono', monospace";

const H = 190;
const PAD_TOP = 14;
const PAD_BOTTOM = 26;
const PAD_LEFT = 46;
const GAP = 2;

/**
 * One measure over time, optionally split into named parts.
 *
 * One axis, one measure — never a second scale for money beside tokens. Those are two measures of
 * different size, and putting them on one plot with two axes lets the eye read a relationship that
 * the numbers do not contain. The toggle above switches which one is being shown instead.
 *
 * Stacking is for parts of the same measure — whose calls these were — where the bar's full height
 * still answers the same question it answered without the split. `keys` arriving empty draws the
 * plain single-colour chart, which is what a stack of one segment would have been anyway.
 *
 * The bars carry a hover tooltip because an SVG chart in a browser is interactive whether or not
 * anybody planned it, and a bar with no way to read its value is a bar that has to be estimated
 * against a gridline.
 */
export default function TimeBarChart({ title, buckets, keys = [], format, theme, action, empty = 'Nothing to show yet' }) {
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

  const stacked = keys.length > 1;
  const plain = palette(theme)[0];
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
  const baseline = PAD_TOP + plotHeight;

  const y = value => baseline - (value / max) * plotHeight;

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
            const dim = hovered != null && hovered !== i;
            return (
              <g key={bucket.label} onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)}>
                {/* A transparent full-height target, so the bar does not have to be hit exactly. */}
                <rect x={PAD_LEFT + i * slot} y={PAD_TOP} width={slot} height={plotHeight} fill="transparent" />

                {stacked
                  ? segments(bucket, keys).map(segment => {
                      const top = y(segment.to);
                      // The gap is carved out of the segment rather than added between them, so
                      // the stack still ends exactly at the bar's true height.
                      const height = Math.max(1, y(segment.from) - top - (segment.last ? 0 : GAP));
                      return (
                        <rect
                          key={segment.key}
                          x={x} y={top} width={barWidth} height={height}
                          rx={segment.last ? 4 : 1}
                          fill={seriesColor(segment.key, keys, theme)}
                          opacity={dim ? 0.4 : 1}
                          style={{ transition: 'opacity 0.12s' }}
                        />
                      );
                    })
                  : (
                    <rect
                      x={x} y={y(bucket.value)} width={barWidth}
                      height={Math.max(2, baseline - y(bucket.value))}
                      rx={4}
                      fill={plain}
                      opacity={dim ? 0.4 : 1}
                      style={{ transition: 'opacity 0.12s' }}
                    />
                  )}

                <text x={x + barWidth / 2} y={H - 9} textAnchor="middle" style={{ fontFamily: mono, fontSize: 9, fill: 'var(--text-faint)' }}>
                  {bucket.label.slice(5)}
                </text>
              </g>
            );
          })}

          {hovered != null && (
            <Tooltip
              bucket={buckets[hovered]}
              keys={stacked ? keys : []}
              theme={theme}
              format={format}
              centre={PAD_LEFT + hovered * slot + slot / 2}
              top={y(buckets[hovered].value)}
              barWidth={barWidth}
              width={width}
            />
          )}
        </svg>
      </div>

      {/*
        Identity is never colour alone: every part is named here as well as in the tooltip. Only
        the parts that are actually drawn, though — in money mode a local model costs nothing, and
        a swatch beside a name that appears in no bar is a legend describing a chart that isn't
        there. The colours still come from the full order, so dropping a name repaints nobody.
      */}
      {stacked && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 16px', marginTop: 12 }}>
          {keys.filter(key => buckets.some(bucket => (bucket.parts?.[key] ?? 0) > 0)).map(key => (
            <span key={key} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--text-dim)' }}>
              <span style={{ width: 9, height: 9, borderRadius: 3, background: seriesColor(key, keys, theme), flexShrink: 0 }} />
              {key}
            </span>
          ))}
        </div>
      )}
    </Panel>
  );
}

/** Running boundaries for one bar, in the fixed series order, skipping the parts that are absent. */
function segments(bucket, keys) {
  const present = keys
    .map(key => ({ key, value: bucket.parts?.[key] ?? 0 }))
    .filter(part => part.value > 0);

  let from = 0;
  return present.map((part, i) => {
    const to = from + part.value;
    const bounds = { key: part.key, value: part.value, from, to, last: i === present.length - 1 };
    from = to;
    return bounds;
  });
}

const clamp = (value, low, high) => Math.min(Math.max(value, low), high);

function Tooltip({ bucket, keys, theme, format, centre, top, barWidth, width }) {
  const lines = keys
    .map(key => ({ key, value: bucket.parts?.[key] ?? 0 }))
    .filter(line => line.value > 0);

  const boxWidth = lines.length ? 168 : 116;
  const height = 40 + lines.length * 15;

  // Above the bar where there is room, beside it where there is not. Clamping a box that does not
  // fit to the top edge is what put the tooltip over the tallest bar in the chart — the one bar
  // anybody hovering was most likely asking about.
  const above = top - height - 8;
  const fitsAbove = above >= 2;
  const y = fitsAbove ? above : clamp(top, 2, H - height - 2);

  let x;
  if (fitsAbove) {
    x = centre - boxWidth / 2;
  } else {
    const right = centre + barWidth / 2 + 10;
    x = right + boxWidth <= width - 2 ? right : centre - barWidth / 2 - 10 - boxWidth;
  }
  x = clamp(x, 2, Math.max(2, width - boxWidth - 2));

  return (
    <g style={{ pointerEvents: 'none' }}>
      <rect x={x} y={y} width={boxWidth} height={height} rx={8}
            fill="var(--surface)" stroke="var(--border-strong)" strokeWidth={1} />
      <text x={x + boxWidth / 2} y={y + 16} textAnchor="middle" style={{ fontFamily: mono, fontSize: 9.5, fill: 'var(--text-faint)' }}>
        {bucket.label}
      </text>
      <text x={x + boxWidth / 2} y={y + 31} textAnchor="middle" style={{ fontFamily: mono, fontSize: 12, fontWeight: 600, fill: 'var(--text)' }}>
        {format(bucket.value)}
      </text>
      {lines.map((line, i) => (
        <g key={line.key}>
          <rect x={x + 10} y={y + 41 + i * 15} width={7} height={7} rx={2} fill={seriesColor(line.key, keys, theme)} />
          <text x={x + 22} y={y + 47.5 + i * 15} style={{ fontFamily: mono, fontSize: 9.5, fill: 'var(--text-dim)' }}>
            {line.key.length > 14 ? `${line.key.slice(0, 13)}…` : line.key}
          </text>
          <text x={x + boxWidth - 10} y={y + 47.5 + i * 15} textAnchor="end" style={{ fontFamily: mono, fontSize: 9.5, fill: 'var(--text)' }}>
            {format(line.value)}
          </text>
        </g>
      ))}
    </g>
  );
}
