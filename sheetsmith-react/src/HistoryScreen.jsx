import { useEffect, useState } from 'react';
import { getHistory, getDownloadUrl } from './api.js';

const mono = "'JetBrains Mono', monospace";

const STATUS_COLOUR = {
  COMPLETED: ['var(--accent-soft)', 'var(--accent-text)'],
  PARTIAL: ['var(--warn-bg)', 'var(--warn)'],
  FAILED: ['var(--del-bg)', 'var(--del)'],
  PROCESSING: ['var(--surface-2)', 'var(--text-dim)'],
};

function Status({ value }) {
  const [bg, fg] = STATUS_COLOUR[value] ?? STATUS_COLOUR.PROCESSING;
  return (
    <span style={{ display: 'inline-block', padding: '2px 8px', borderRadius: 6, background: bg, color: fg, fontFamily: mono, fontSize: 11, letterSpacing: '0.03em' }}>
      {value.toLowerCase()}
    </span>
  );
}

function when(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

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

  const rows = data?.content ?? [];
  const cell = { padding: '11px 14px', borderBottom: '1px solid var(--border)', fontSize: 13.5, verticalAlign: 'top' };
  const head = { ...cell, fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)', fontWeight: 500, borderBottom: '1px solid var(--border-strong)' };

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

      {/* Two different emptinesses, said differently: nothing has happened yet, versus nothing is
          loaded. A spinner in place of "no runs yet" reads as a page that never finishes. */}
      {loading && !data && (
        <div style={{ padding: '48px 0', textAlign: 'center', color: 'var(--text-faint)', fontSize: 14 }}>Loading…</div>
      )}

      {!loading && rows.length === 0 && (
        <div style={{ padding: '56px 24px', textAlign: 'center', border: '1px dashed var(--border-strong)', borderRadius: 14 }}>
          <div style={{ fontSize: 26, color: 'var(--text-faint)', marginBottom: 10 }}>⧗</div>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 4 }}>No runs yet</div>
          <div style={{ fontSize: 13.5, color: 'var(--text-dim)' }}>
            Improve a spreadsheet and it will show up here.
          </div>
        </div>
      )}

      {rows.length > 0 && (
        <div style={{ border: '1px solid var(--border-strong)', borderRadius: 14, background: 'var(--surface)', overflow: 'hidden' }}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={{ ...head, textAlign: 'left' }}>When</th>
                  <th style={{ ...head, textAlign: 'left' }}>Status</th>
                  <th style={{ ...head, textAlign: 'left' }}>File</th>
                  <th style={{ ...head, textAlign: 'left' }}>Instruction</th>
                  <th style={{ ...head, textAlign: 'left' }}>Who</th>
                  <th style={{ ...head, textAlign: 'right' }}>Tokens</th>
                  <th style={{ ...head, textAlign: 'left' }}>Model</th>
                  <th style={head} />
                </tr>
              </thead>
              <tbody>
                {rows.map(run => (
                  <tr key={run.id}>
                    <td style={{ ...cell, fontFamily: mono, fontSize: 12, color: 'var(--text-dim)', whiteSpace: 'nowrap' }}>{when(run.createdAt)}</td>
                    <td style={cell}><Status value={run.status} /></td>
                    <td style={{ ...cell, fontFamily: mono, fontSize: 12 }}>{run.inputFilename}</td>
                    <td style={{ ...cell, maxWidth: 320, color: 'var(--text-dim)' }}>{run.instruction}</td>
                    {/* A dash, not a made-up name: with authentication off nobody owns a run. */}
                    <td style={{ ...cell, color: run.startedByName ? 'var(--text)' : 'var(--text-faint)' }}>
                      {run.startedByName ?? '—'}
                    </td>
                    <td style={{ ...cell, fontFamily: mono, fontSize: 12, textAlign: 'right', color: run.totalTokens == null ? 'var(--text-faint)' : 'var(--text)' }}>
                      {/* Null means the provider reported nothing, which is not the same as zero. */}
                      {run.totalTokens == null ? '—' : run.totalTokens.toLocaleString()}
                    </td>
                    <td style={{ ...cell, fontFamily: mono, fontSize: 12, color: 'var(--text-dim)' }}>{run.model ?? '—'}</td>
                    <td style={{ ...cell, textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {run.status !== 'FAILED' && (
                        <a
                          href={getDownloadUrl(run.id)}
                          style={{ fontSize: 12.5, color: 'var(--accent-text)', textDecoration: 'none', fontWeight: 500 }}
                        >
                          Download
                        </a>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 14, marginTop: 20 }}>
          <button
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0}
            style={{ height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, cursor: page === 0 ? 'default' : 'pointer', opacity: page === 0 ? 0.5 : 1 }}
          >
            Previous
          </button>
          <span style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-faint)' }}>
            {page + 1} / {data.totalPages}
          </span>
          <button
            onClick={() => setPage(p => Math.min(data.totalPages - 1, p + 1))}
            disabled={page >= data.totalPages - 1}
            style={{ height: 32, padding: '0 14px', borderRadius: 8, border: '1px solid var(--border-strong)', background: 'transparent', color: 'var(--text-dim)', fontFamily: 'inherit', fontSize: 13, cursor: page >= data.totalPages - 1 ? 'default' : 'pointer', opacity: page >= data.totalPages - 1 ? 0.5 : 1 }}
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
