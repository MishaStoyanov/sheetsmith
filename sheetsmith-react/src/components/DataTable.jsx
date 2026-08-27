const mono = "'JetBrains Mono', monospace";

/**
 * A table with the three states a list actually has: loading, empty, and rows.
 *
 * Those three are the point. Every hand-rolled table in an app eventually renders empty headers
 * while it waits, or a bare frame when there is nothing — and both read as a page that is broken
 * rather than a page that is finished.
 *
 * `empty` and `emptyFiltered` are separate on purpose: "nothing has ever happened" and "your filter
 * matched nothing" call for different words and different next actions, and a single message has to
 * be wrong about one of them.
 *
 * @param columns  [{ key, header, align, width, sortable, render(row) }]
 * @param sort     { key, direction } — the column currently ordering the rows, if any
 */
export default function DataTable({
  columns,
  rows,
  rowKey = row => row.id,
  loading = false,
  filtered = false,
  empty,
  emptyFiltered,
  sort,
  onSortChange,
}) {
  const cell = { padding: '11px 14px', borderBottom: '1px solid var(--border)', fontSize: 13.5, verticalAlign: 'top' };
  const head = {
    ...cell,
    fontFamily: mono, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em',
    color: 'var(--text-faint)', fontWeight: 500, borderBottom: '1px solid var(--border-strong)',
    whiteSpace: 'nowrap',
  };

  if (loading && rows.length === 0) {
    return (
      <div style={{ padding: '48px 0', textAlign: 'center', color: 'var(--text-faint)', fontSize: 14 }}>
        Loading…
      </div>
    );
  }

  if (rows.length === 0) {
    const state = (filtered ? emptyFiltered : empty) ?? empty;
    return (
      <div style={{ padding: '56px 24px', textAlign: 'center', border: '1px dashed var(--border-strong)', borderRadius: 14 }}>
        {state?.icon && <div style={{ fontSize: 26, color: 'var(--text-faint)', marginBottom: 10 }}>{state.icon}</div>}
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 4 }}>{state?.title}</div>
        {state?.hint && <div style={{ fontSize: 13.5, color: 'var(--text-dim)' }}>{state.hint}</div>}
      </div>
    );
  }

  const toggle = (key) => {
    if (!onSortChange) return;
    const direction = sort?.key === key && sort.direction === 'asc' ? 'desc' : 'asc';
    onSortChange({ key, direction });
  };

  return (
    <div style={{ border: '1px solid var(--border-strong)', borderRadius: 14, background: 'var(--surface)', overflow: 'hidden', opacity: loading ? 0.6 : 1, transition: 'opacity 0.15s' }}>
      {/* Wide tables scroll inside their own frame; the page itself must never scroll sideways. */}
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {columns.map(column => {
                const sorted = sort?.key === column.key;
                return (
                  <th
                    key={column.key}
                    onClick={column.sortable ? () => toggle(column.key) : undefined}
                    style={{
                      ...head,
                      textAlign: column.align ?? 'left',
                      width: column.width,
                      cursor: column.sortable ? 'pointer' : 'default',
                      color: sorted ? 'var(--accent-text)' : head.color,
                      userSelect: 'none',
                    }}
                  >
                    {column.header}
                    {column.sortable && (
                      <span style={{ marginLeft: 5, opacity: sorted ? 1 : 0.3 }}>
                        {sorted && sort.direction === 'desc' ? '↓' : '↑'}
                      </span>
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={rowKey(row)}>
                {columns.map(column => (
                  <td key={column.key} style={{ ...cell, textAlign: column.align ?? 'left' }}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
