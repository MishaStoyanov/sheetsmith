import { useEffect, useState } from 'react';
import Badge from './components/Badge.jsx';
import DataTable from './components/DataTable.jsx';
import Pagination from './components/Pagination.jsx';
import { getHistory, getDownloadUrl } from './api.js';

const mono = "'JetBrains Mono', monospace";

const TONE = { COMPLETED: 'good', PARTIAL: 'warn', FAILED: 'bad', PROCESSING: 'neutral' };

function when(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

const dim = { fontFamily: mono, fontSize: 12, color: 'var(--text-dim)' };

/**
 * Every run this instance has made.
 *
 * Reads the paginated endpoint that has existed all along, so this screen is useful from the first
 * commit that adds it; filters and sorting arrive with the search endpoint and replace the paging
 * controls rather than sitting beside them.
 */
export default function HistoryScreen() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    // The same reset-on-change shape the settings panel uses, and for the same reason: the fix is
    // a key from the parent, not a rewrite of the loading flags.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    getHistory(page)
      .then(result => { if (!cancelled) { setData(result); setError(null); } })
      .catch(e => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [page]);

  const columns = [
    { key: 'createdAt', header: 'When', render: run => <span style={{ ...dim, whiteSpace: 'nowrap' }}>{when(run.createdAt)}</span> },
    { key: 'status', header: 'Status', render: run => <Badge tone={TONE[run.status]}>{run.status.toLowerCase()}</Badge> },
    { key: 'inputFilename', header: 'File', render: run => <span style={{ fontFamily: mono, fontSize: 12 }}>{run.inputFilename}</span> },
    { key: 'instruction', header: 'Instruction', render: run => <span style={{ color: 'var(--text-dim)' }}>{run.instruction}</span> },
    {
      key: 'startedBy',
      header: 'Who',
      // A dash, not a made-up name: with authentication off nobody owns a run.
      render: run => <span style={{ color: run.startedByName ? 'var(--text)' : 'var(--text-faint)' }}>{run.startedByName ?? '—'}</span>,
    },
    {
      key: 'totalTokens',
      header: 'Tokens',
      align: 'right',
      // Null means the provider reported nothing, which is not the same as a run that cost zero.
      render: run => (
        <span style={{ fontFamily: mono, fontSize: 12, color: run.totalTokens == null ? 'var(--text-faint)' : 'var(--text)' }}>
          {run.totalTokens == null ? '—' : run.totalTokens.toLocaleString()}
        </span>
      ),
    },
    { key: 'model', header: 'Model', render: run => <span style={dim}>{run.model ?? '—'}</span> },
    {
      key: 'download',
      header: '',
      align: 'right',
      render: run => run.status !== 'FAILED' && (
        <a href={getDownloadUrl(run.id)} style={{ fontSize: 12.5, color: 'var(--accent-text)', textDecoration: 'none', fontWeight: 500, whiteSpace: 'nowrap' }}>
          Download
        </a>
      ),
    },
  ];

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto', padding: '40px 28px 100px' }}>
      <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.01em', margin: '0 0 6px' }}>History</h1>
      <p style={{ fontSize: 14, color: 'var(--text-dim)', margin: '0 0 26px' }}>
        Every run this instance has made, newest first.
      </p>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--del-bg)', color: 'var(--del)', border: '1px solid var(--del)', fontSize: 13.5, marginBottom: 18 }}>
          {error}
        </div>
      )}

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        loading={loading}
        empty={{ icon: '⧗', title: 'No runs yet', hint: 'Improve a spreadsheet and it will show up here.' }}
      />

      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />
    </div>
  );
}
