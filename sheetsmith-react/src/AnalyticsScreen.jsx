import { useCallback, useEffect, useState } from 'react';
import Button from './components/Button.jsx';
import DateRange from './components/DateRange.jsx';
import DonutChart, { Panel } from './components/DonutChart.jsx';
import FilterBar from './components/FilterBar.jsx';
import TimeBarChart from './components/TimeBarChart.jsx';
import { getAnalyticsSummary } from './api.js';

const mono = "'JetBrains Mono', monospace";

const GRANULARITIES = [
  { value: 'day', label: 'Days' },
  { value: 'week', label: 'Weeks' },
  { value: 'month', label: 'Months' },
  { value: 'year', label: 'Years' },
];

const EMPTY = { from: '', to: '' };

function tokens(value, short = false) {
  if (value == null) return '—';
  if (short) {
    if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
    // A decimal below ten thousand: rounding whole gave an axis reading 0 / 2k / 3k for a
    // midpoint that was 1,566 â a scale whose halfway mark was not half of its top.
    if (value >= 10_000) return `${Math.round(value / 1_000)}k`;
    if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
    return `${value}`;
  }
  return value.toLocaleString();
}

function money(value, short = false) {
  if (value == null) return '—';
  const n = Number(value);
  if (!short) return `$${n.toFixed(n < 1 ? 4 : 2)}`;

  // Axis labels keep enough places to stay distinct. Two decimals collapsed a scale that went to
  // two cents into three identical $0.00 marks — a ruler with no divisions on it.
  if (n >= 1000) return `$${Math.round(n / 1000)}k`;
  if (n >= 1) return `$${n.toFixed(0)}`;
  if (n >= 0.01) return `$${n.toFixed(2)}`;
  return `$${n.toFixed(4)}`;
}

/** Spend and volume, with one switch between the two measures rather than two axes on one plot. */
export default function AnalyticsScreen({ theme, authEnabled }) {
  const [filters, setFilters] = useState(EMPTY);
  const [granularity, setGranularity] = useState('day');
  const [measure, setMeasure] = useState('tokens');

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return getAnalyticsSummary({
      from: filters.from ? `${filters.from}T00:00:00` : null,
      to: filters.to ? `${filters.to}T23:59:59` : null,
      granularity,
    })
      .then(result => { setData(result); setError(null); })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [filters, granularity]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  // Money is offered only when it can be answered. With no prices entered — how every instance
  // starts — the switch is not shown at all rather than shown and answering "$0.00".
  const showMoney = !!data?.costKnown;
  const asMoney = showMoney && measure === 'money';
  const format = asMoney ? money : tokens;
  const value = slice => (asMoney ? slice.cost : slice.totalTokens);

  const activeCount = (filters.from || filters.to) ? 1 : 0;
  const totals = data?.totals;

  // Only where it says something: a breakdown by person on an instance where every call belongs to
  // the same person (or to nobody) is one bar labelled with their name.
  const peopleWorthShowing = (data?.byUser ?? []).length > 1;

  return (
    <div style={{ maxWidth: 1180, margin: '0 auto', padding: '40px 28px 100px' }}>
      <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 6px' }}>Analytics</h1>
      <p style={{ fontSize: 14, color: 'var(--text-dim)', margin: '0 0 22px' }}>
        What this instance has asked models to do, and what it cost.
      </p>

      <FilterBar activeCount={activeCount} onClear={() => setFilters(EMPTY)}>
        <DateRange
          label="Between"
          from={filters.from}
          to={filters.to}
          onChange={({ from, to }) => setFilters({ from, to })}
        />

        <div>
          <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>Grouped by</span>
          <div style={{ display: 'flex', gap: 4 }}>
            {GRANULARITIES.map(g => (
              <Button
                key={g.value}
                size="sm"
                variant={granularity === g.value ? 'primary' : 'secondary'}
                onClick={() => setGranularity(g.value)}
              >
                {g.label}
              </Button>
            ))}
          </div>
        </div>

        {showMoney && (
          <div>
            <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>Measure</span>
            <div style={{ display: 'flex', gap: 4 }}>
              <Button size="sm" variant={measure === 'tokens' ? 'primary' : 'secondary'} onClick={() => setMeasure('tokens')}>
                Tokens
              </Button>
              <Button size="sm" variant={measure === 'money' ? 'primary' : 'secondary'} onClick={() => setMeasure('money')}>
                Money
              </Button>
            </div>
          </div>
        )}
      </FilterBar>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5, marginBottom: 18 }}>
          {error}
        </div>
      )}

      {/* Said out loud rather than left to be inferred from a smaller number. */}
      {data && !data.costKnown && (
        <Note>
          No prices have been entered, so spend is shown in tokens. Add prices in settings to see
          money.
        </Note>
      )}
      {data?.costKnown && data.unpricedModels.length > 0 && (
        <Note>
          {data.unpricedModels.length === 1 ? 'One model has' : `${data.unpricedModels.length} models have`}
          {' '}no price, so their calls are counted in tokens but not in money:{' '}
          <span style={{ fontFamily: mono, fontSize: 12 }}>{data.unpricedModels.join(', ')}</span>
        </Note>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 12, marginBottom: 16 }}>
        <Stat label="Calls" value={totals ? totals.calls.toLocaleString() : '—'} />
        <Stat label="Tokens" value={totals ? tokens(totals.totalTokens) : '—'} />
        <Stat label="Spend" value={totals?.cost != null ? money(totals.cost) : '—'}
              hint={totals?.cost == null ? 'no prices entered' : undefined} />
        <Stat label="Documents" value={totals ? totals.documents.toLocaleString() : '—'} />
      </div>

      <div style={{ display: 'grid', gap: 12, marginBottom: 12 }}>
        <TimeBarChart
          title={asMoney ? 'Spend over time' : 'Tokens over time'}
          buckets={(data?.overTime ?? []).map(b => ({ label: b.label, value: value(b) ?? 0 }))}
          format={format}
          theme={theme}
          empty={loading ? 'Loading…' : 'No calls in this range'}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 12 }}>
        <DonutChart
          title={asMoney ? 'Spend by provider' : 'Tokens by provider'}
          slices={(data?.byProvider ?? []).map(s => ({ label: s.label, value: value(s) ?? 0 }))}
          format={format}
          theme={theme}
          empty={loading ? 'Loading…' : 'No calls in this range'}
        />

        {peopleWorthShowing && authEnabled && (
          <DonutChart
            title={asMoney ? 'Spend by person' : 'Tokens by person'}
            slices={data.byUser.map(s => ({ label: s.name, value: value(s) ?? 0 }))}
            format={format}
            theme={theme}
          />
        )}

        <DonutChart
          title={asMoney ? 'Spend by model' : 'Tokens by model'}
          slices={(data?.byModel ?? []).slice(0, 6).map(s => ({ label: s.label, value: value(s) ?? 0 }))}
          format={format}
          theme={theme}
          empty={loading ? 'Loading…' : 'No calls in this range'}
        />
      </div>
    </div>
  );
}

function Stat({ label, value, hint }) {
  return (
    <Panel title={label}>
      <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 600, letterSpacing: '-0.01em' }}>{value}</div>
      {hint && <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 4 }}>{hint}</div>}
    </Panel>
  );
}

function Note({ children }) {
  return (
    <div style={{ padding: '10px 14px', borderRadius: 10, background: 'var(--surface-2)', border: '1px solid var(--border)', color: 'var(--text-dim)', fontSize: 13, marginBottom: 16, lineHeight: 1.5 }}>
      {children}
    </div>
  );
}
