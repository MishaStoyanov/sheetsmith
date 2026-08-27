import { useCallback, useEffect, useState } from 'react';
import Badge from './components/Badge.jsx';
import Button from './components/Button.jsx';
import DataTable from './components/DataTable.jsx';
import DateRange from './components/DateRange.jsx';
import FilterBar from './components/FilterBar.jsx';
import MultiSelect from './components/MultiSelect.jsx';
import Pagination from './components/Pagination.jsx';
import { deleteJob, getDownloadUrl, getJobDetail, searchHistory } from './api.js';
import { searchUsers } from './settingsApi.js';

const mono = "'JetBrains Mono', monospace";

const TONE = { COMPLETED: 'good', PARTIAL: 'warn', FAILED: 'bad', PROCESSING: 'neutral' };
const STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED', 'PROCESSING'];

const EMPTY = { keyword: '', from: '', to: '', statuses: [], owners: [], includeUnowned: false };

function when(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

function took(run) {
  if (!run.processingStartedAt || !run.processingFinishedAt) return '—';
  const ms = new Date(run.processingFinishedAt) - new Date(run.processingStartedAt);
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

const dim = { fontFamily: mono, fontSize: 12, color: 'var(--text-dim)' };

/** Every run this instance has made, with the filters the search endpoint understands. */
export default function HistoryScreen({ authEnabled }) {
  const [filters, setFilters] = useState(EMPTY);
  const [sort, setSort] = useState({ key: 'createdAt', direction: 'desc' });
  const [page, setPage] = useState(0);

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [people, setPeople] = useState([]);

  const [openRun, setOpenRun] = useState(null);
  const [detail, setDetail] = useState(null);

  const set = (patch) => {
    setFilters(f => ({ ...f, ...patch }));
    // Any change to what is being asked for starts from the first page: staying on page four of a
    // narrower result is how a screen ends up looking empty for no reason.
    setPage(0);
  };

  // Counted from the filters rather than tracked separately, so it cannot disagree with them.
  const activeCount = (filters.keyword ? 1 : 0) + (filters.from || filters.to ? 1 : 0)
    + (filters.statuses.length ? 1 : 0) + (filters.owners.length || filters.includeUnowned ? 1 : 0);

  const load = useCallback(() => {
    setLoading(true);
    return searchHistory({
      keyword: filters.keyword || null,
      from: filters.from ? `${filters.from}T00:00:00` : null,
      // Inclusive, because "to 20 August" plainly includes the twentieth.
      to: filters.to ? `${filters.to}T23:59:59` : null,
      statuses: filters.statuses.length ? filters.statuses : null,
      userIds: filters.owners.length ? filters.owners : null,
      includeUnowned: filters.includeUnowned,
      page,
      size: 20,
      sort: sort.key,
      direction: sort.direction,
    })
      .then(result => {
        setData(result);
        setError(null);
        // The open run may not be in the new result — a filter narrowed past it, or it was
        // just deleted. Leaving its steps on screen under a table that no longer lists it
        // is how a detail panel starts describing the wrong thing.
        setOpenRun(open => (result.content.some(run => run.id === open) ? open : null));
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [filters, page, sort]);

  useEffect(() => {
    // The fetch flips a loading flag on its way out, which the rule reads as a cascading
    // render. It is the one every list does: ask, show that you are asking, then show what
    // came back.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  useEffect(() => {
    // The owner filter only exists where runs can have owners.
    if (!authEnabled) return;
    searchUsers(null).then(setPeople).catch(() => {});
  }, [authEnabled]);

  const expand = async (run) => {
    if (openRun === run.id) { setOpenRun(null); return; }
    setOpenRun(run.id);
    setDetail(null);
    try {
      setDetail(await getJobDetail(run.id));
    } catch (e) {
      setError(e.message);
    }
  };

  const remove = async (run) => {
    try {
      await deleteJob(run.id);
      if (openRun === run.id) setOpenRun(null);
      await load();
    } catch (e) {
      setError(e.message);
    }
  };

  const columns = [
    {
      key: 'createdAt', header: 'When', sortable: true,
      render: run => <span style={{ ...dim, whiteSpace: 'nowrap' }}>{when(run.createdAt)}</span>,
    },
    {
      key: 'status', header: 'Status', sortable: true,
      render: run => <Badge tone={TONE[run.status]}>{run.status.toLowerCase()}</Badge>,
    },
    {
      key: 'inputFilename', header: 'File', sortable: true,
      render: run => <span style={{ fontFamily: mono, fontSize: 12 }}>{run.inputFilename}</span>,
    },
    {
      key: 'instruction', header: 'Instruction',
      render: run => (
        <button
          onClick={() => expand(run)}
          style={{ background: 'none', border: 'none', padding: 0, textAlign: 'left', color: 'var(--text-dim)', font: 'inherit', fontSize: 13.5, cursor: 'pointer' }}
        >
          {run.instruction}
          <span style={{ marginLeft: 6, fontSize: 10, color: 'var(--text-faint)' }}>
            {openRun === run.id ? '▴' : '▾'}
          </span>
        </button>
      ),
    },
    {
      key: 'startedBy', header: 'Who', sortable: true,
      // A dash, not a made-up name: with authentication off nobody owns a run.
      render: run => <span style={{ color: run.startedByName ? 'var(--text)' : 'var(--text-faint)' }}>{run.startedByName ?? '—'}</span>,
    },
    { key: 'duration', header: 'Took', align: 'right', sortable: true, render: run => <span style={dim}>{took(run)}</span> },
    {
      key: 'totalTokens', header: 'Tokens', align: 'right', sortable: true,
      // Null means the provider reported nothing, which is not the same as a run that cost zero.
      render: run => (
        <span style={{ fontFamily: mono, fontSize: 12, color: run.totalTokens == null ? 'var(--text-faint)' : 'var(--text)' }}>
          {run.totalTokens == null ? '—' : run.totalTokens.toLocaleString()}
        </span>
      ),
    },
    { key: 'model', header: 'Model', sortable: true, render: run => <span style={dim}>{run.model ?? '—'}</span> },
    {
      key: 'actions', header: '', align: 'right',
      render: run => (
        <span style={{ display: 'inline-flex', gap: 10, alignItems: 'center', whiteSpace: 'nowrap' }}>
          {run.status !== 'FAILED' && (
            <a href={getDownloadUrl(run.id)} style={{ fontSize: 12.5, color: 'var(--accent-text)', textDecoration: 'none', fontWeight: 500 }}>
              Download
            </a>
          )}
          <Button size="sm" variant="ghost" onClick={() => remove(run)} style={{ color: 'var(--del)' }}>
            Delete
          </Button>
        </span>
      ),
    },
  ];

  const rows = data?.content ?? [];

  return (
    <div style={{ maxWidth: 1280, margin: '0 auto', padding: '40px 28px 100px' }}>
      <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 6px' }}>History</h1>
      <p style={{ fontSize: 14, color: 'var(--text-dim)', margin: '0 0 22px' }}>
        Every run this instance has made. Click an instruction to see the steps it applied.
      </p>

      <FilterBar activeCount={activeCount} onClear={() => { setFilters(EMPTY); setPage(0); }}>
        <div>
          <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>Search</span>
          <input
            value={filters.keyword}
            onChange={e => set({ keyword: e.target.value })}
            placeholder="Instruction or file name"
            style={{ height: 34, width: 230, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'var(--surface-2)', color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
          />
        </div>

        <DateRange
          label="Run between"
          from={filters.from}
          to={filters.to}
          onChange={({ from, to }) => set({ from, to })}
        />

        <MultiSelect
          label="Status"
          options={STATUSES.map(s => ({ value: s, label: s.toLowerCase() }))}
          value={filters.statuses}
          onChange={statuses => set({ statuses })}
        />

        {/* Only where runs can have an owner at all. */}
        {authEnabled && (
          <>
            <MultiSelect
              label="Started by"
              options={people.map(p => ({ value: p.id, label: p.name }))}
              value={filters.owners}
              onChange={owners => set({ owners })}
            />
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, color: 'var(--text-dim)', height: 34, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={filters.includeUnowned}
                onChange={e => set({ includeUnowned: e.target.checked })}
              />
              {/* Runs made before accounts existed have no id to be named by. */}
              Include runs with no owner
            </label>
          </>
        )}
      </FilterBar>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5, marginBottom: 18 }}>
          {error}
        </div>
      )}

      <DataTable
        columns={columns}
        rows={rows}
        loading={loading}
        filtered={activeCount > 0}
        empty={{ icon: '⧗', title: 'No runs yet', hint: 'Improve a spreadsheet and it will show up here.' }}
        emptyFiltered={{ icon: '⧗', title: 'Nothing matches these filters', hint: 'Widen the range, or clear them.' }}
        sort={sort}
        onSortChange={next => { setSort(next); setPage(0); }}
      />

      {openRun != null && (
        <div style={{ marginTop: 16, border: '1px solid var(--border-strong)', borderRadius: 14, background: 'var(--surface)', padding: '16px 18px' }}>
          {!detail && <div style={{ fontSize: 13.5, color: 'var(--text-faint)' }}>Loading the steps…</div>}
          {detail && (
            <>
              <div style={{ fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)', marginBottom: 10 }}>
                What this run did
              </div>
              {detail.errorMessage && (
                <div style={{ padding: '9px 12px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', fontSize: 13, marginBottom: 12 }}>
                  {detail.errorMessage}
                </div>
              )}
              {detail.appliedActions.length === 0 && (
                <div style={{ fontSize: 13.5, color: 'var(--text-dim)' }}>No steps were applied.</div>
              )}
              {detail.appliedActions.map((step, i) => (
                <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'baseline', padding: '6px 0', borderBottom: i < detail.appliedActions.length - 1 ? '1px solid var(--border)' : 'none' }}>
                  <span style={{ color: step.success ? 'var(--accent)' : 'var(--del)', fontSize: 12 }}>
                    {step.success ? '✓' : '✕'}
                  </span>
                  <span style={{ fontSize: 13.5 }}>{step.description}</span>
                  {step.errorMessage && (
                    <span style={{ fontSize: 12.5, color: 'var(--del)' }}>{step.errorMessage}</span>
                  )}
                </div>
              ))}
            </>
          )}
        </div>
      )}

      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />
    </div>
  );
}
