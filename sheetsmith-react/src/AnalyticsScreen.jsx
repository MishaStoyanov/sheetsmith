import { useCallback, useEffect, useState } from 'react';
import Button from './components/Button.jsx';
import DateRange from './components/DateRange.jsx';
import DonutChart, { Panel } from './components/DonutChart.jsx';
import FilterBar from './components/FilterBar.jsx';
import Note from './components/Note.jsx';
import UnpricedModelsNote from './components/UnpricedModelsNote.jsx';
import RankedBars from './components/RankedBars.jsx';
import StatusBar from './components/StatusBar.jsx';
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

/**
 * Seconds as something a person reads at a glance, not as a number of seconds.
 *
 * Kept to a decimal below ten, because rounding is what turned a median of four tenths of a second
 * into "0s" — a figure that reads as a broken counter rather than as a fast run.
 */
function duration(seconds) {
  if (seconds == null) return '—';
  if (seconds < 10) return `${seconds.toFixed(1)}s`;
  const whole = Math.round(seconds);
  if (whole < 60) return `${whole}s`;
  const minutes = Math.floor(whole / 60);
  return minutes < 60 ? `${minutes}m ${whole % 60}s` : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
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
export default function AnalyticsScreen({ theme, user }) {
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
  const runs = data?.runs;

  // Your own share, taken out of the same answer as everything else rather than asked for
  // separately — a second call could come back disagreeing with the totals above it.
  //
  // Without accounts there is no "yours": every call on the instance is the same anonymous pile,
  // and the totals already are that pile. So the panel is simply not drawn, instead of drawn
  // around a number identical to the one beside it.
  const mine = user ? (data?.byUser ?? []).find(person => person.userId === user.id) : null;
  const shareOfTokens = mine && totals?.totalTokens
    ? Math.round((mine.totalTokens / totals.totalTokens) * 100)
    : null;

  // Only where it says something: a breakdown by person on an instance where every call belongs to
  // the same person (or to nobody) is one bar labelled with their name. The server decides it —
  // the question is about the data, and asking here would let the two charts disagree.
  const peopleWorthShowing = (data?.byUser ?? []).length > 1;
  const splitByPerson = (data?.overTimeByUser ?? []).length > 0;

  // The series order comes from the by-person totals, so the same person keeps the same colour in
  // both charts and a date filter that drops someone does not repaint everyone else.
  const people = splitByPerson
    ? data.byUser.map(person => person.name).filter(name => data.overTimeByUser.some(row => row.name === name))
    : [];

  const overTime = (data?.overTime ?? []).map(bucket => {
    if (!splitByPerson) {
      return { label: bucket.label, value: value(bucket) ?? 0 };
    }
    // The bar is the sum of its own parts rather than the separately-rounded total: a stack that
    // overshoots its own axis by a hundredth of a cent is a bug people can see.
    const parts = {};
    for (const row of data.overTimeByUser) {
      if (row.label === bucket.label) parts[row.name] = (parts[row.name] ?? 0) + (value(row) ?? 0);
    }
    const total = Object.values(parts).reduce((sum, part) => sum + part, 0);
    return { label: bucket.label, value: total, parts };
  });

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
      {data?.costKnown && <UnpricedModelsNote models={data.unpricedModels} />}

      {user && (
        <div style={{ marginBottom: 16 }}>
          <Panel title={`Your numbers${activeCount ? ', in this range' : ''}`}>
            {mine ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 18 }}>
                <Mine label="Requests" value={mine.calls.toLocaleString()} />
                <Mine label="Documents" value={mine.documents.toLocaleString()}
                      hint="counted as documents opened" />
                <Mine label="Tokens" value={tokens(mine.totalTokens)}
                      hint={shareOfTokens == null ? undefined : `${shareOfTokens}% of this instance`} />
                <Mine label="Spend" value={mine.cost != null ? money(mine.cost) : '—'}
                      hint={mine.cost == null ? 'no prices entered' : undefined} />
              </div>
            ) : (
              <div style={{ fontSize: 13, color: 'var(--text-faint)' }}>
                {loading ? 'Loading…' : 'Nothing of yours in this range yet.'}
              </div>
            )}
          </Panel>
        </div>
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
          buckets={overTime}
          keys={people}
          format={format}
          theme={theme}
          empty={loading ? 'Loading…' : 'No calls in this range'}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 12, marginBottom: 28 }}>
        <DonutChart
          title={asMoney ? 'Spend by provider' : 'Tokens by provider'}
          slices={(data?.byProvider ?? []).map(s => ({ label: s.label, value: value(s) ?? 0 }))}
          format={format}
          theme={theme}
          empty={loading ? 'Loading…' : 'No calls in this range'}
        />

        {peopleWorthShowing && (
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

      {/*
        A second question, under the same filters. The charts above are about what was asked of a
        model; these are about whether the run around it worked — which for anyone hosting this is
        the more useful of the two.
      */}
      <h2 style={{ fontSize: 16, fontWeight: 650, letterSpacing: '-0.01em', margin: '0 0 4px' }}>How runs went</h2>
      <p style={{ fontSize: 13.5, color: 'var(--text-dim)', margin: '0 0 16px' }}>
        Whether the work finished, what it was asked to do, and what went wrong.
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 12, marginBottom: 12 }}>
        <Stat label="Runs" value={runs ? runs.total.toLocaleString() : '—'} />
        <Stat
          label="Finished cleanly"
          value={runs?.successRate == null ? '—' : `${Math.round(runs.successRate * 100)}%`}
          hint={runs?.successRate == null ? 'nothing has finished yet' : 'of the runs that reached a verdict'}
        />
        <Stat label="Median run" value={duration(runs?.medianSeconds)} hint="median, not average" />
      </div>

      <div style={{ marginBottom: 12 }}>
        <Panel title="How they ended">
          <StatusBar counts={runs?.byStatus ?? []} />
        </Panel>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 12 }}>
        <RankedBars
          title="Most used actions"
          rows={runs?.topActions ?? []}
          empty={loading ? 'Loading…' : 'No actions in this range'}
        />
        <RankedBars
          title="What went wrong"
          rows={runs?.topErrors ?? []}
          colour="var(--del)"
          empty={loading ? 'Loading…' : 'Nothing failed in this range'}
        />
      </div>
    </div>
  );
}

/** One figure inside the "your numbers" panel — the panel titles them, so each only needs a word. */
function Mine({ label, value, hint }) {
  return (
    <div>
      <div style={{ fontSize: 12, color: 'var(--text-dim)', marginBottom: 3 }}>{label}</div>
      <div style={{ fontFamily: mono, fontSize: 21, fontWeight: 600, letterSpacing: '-0.01em' }}>{value}</div>
      {hint && <div style={{ fontSize: 11.5, color: 'var(--text-faint)', marginTop: 3 }}>{hint}</div>}
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

