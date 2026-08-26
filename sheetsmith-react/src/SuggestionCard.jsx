
const mono = "'JetBrains Mono', monospace";

const EDITABLE_FIELDS = {
  FORMAT_CELLS:            [{ key: 'range',       label: 'Range' }],
  CREATE_CHART:            [{ key: 'sourceRange', label: 'Source range' }, { key: 'title', label: 'Title' }],
  ADD_SHEET:               [{ key: 'name',        label: 'Name' }],
  ADD_FORMULA:             [{ key: 'cell',        label: 'Cell' }, { key: 'formula', label: 'Formula' }],
  SORT_DATA:               [{ key: 'range',       label: 'Range' }],
  FILTER_DATA:             [{ key: 'range',       label: 'Range' }],
  CONDITIONAL_FORMATTING:  [{ key: 'range',       label: 'Range' }, { key: 'value', label: 'Value' }],
  MERGE_CELLS:             [{ key: 'range',       label: 'Range' }],
  CLEAR_CELLS:             [{ key: 'range',       label: 'Range' }],
  RENAME_SHEET:            [{ key: 'newName',     label: 'New name' }],
  RENAME_COLUMN:           [{ key: 'cell',        label: 'Header' }, { key: 'newName', label: 'New name' }],
  RENAME_CHART_TITLE:      [{ key: 'newTitle',    label: 'New title' }],
  RENAME_CHART_AXIS:       [{ key: 'newTitle',    label: 'Label' }],
};

export default function SuggestionCard({ item, properties, onToggle, onEdit, onCommitEdit }) {
  const dismissed = item.status === 'dismissed';
  const fields = EDITABLE_FIELDS[item.type] ?? [];

  return (
    <div
      onClick={onToggle}
      style={{
        border: `1px solid ${dismissed ? 'var(--border)' : 'var(--border-strong)'}`,
        borderRadius: '12px',
        background: dismissed ? 'var(--surface-2)' : 'var(--surface)',
        padding: '14px',
        cursor: 'pointer',
        opacity: dismissed ? 0.45 : 1,
        transition: 'opacity 0.15s, background 0.15s, border-color 0.15s',
        userSelect: 'none',
      }}
    >
      {/* Top row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <div style={{
          width: '26px', height: '26px', borderRadius: '7px',
          background: 'var(--accent-soft)', color: 'var(--accent-text)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: mono, fontSize: '13px', flexShrink: 0,
        }}>
          {item.mark}
        </div>

        <div style={{
          fontSize: '10.5px', letterSpacing: '0.08em', textTransform: 'uppercase',
          color: 'var(--text-faint)', fontFamily: mono,
        }}>
          {item.cat}
        </div>

        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            fontFamily: mono, fontSize: '11px', color: 'var(--text-dim)',
            background: 'var(--surface-2)', padding: '2px 7px', borderRadius: '5px',
          }}>
            {item.ref}
          </div>

          <div style={{
            width: 18, height: 18, borderRadius: 5, flexShrink: 0,
            border: dismissed ? '1.5px solid var(--border-strong)' : 'none',
            background: dismissed ? 'transparent' : 'var(--accent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 11, color: 'var(--on-accent)',
            transition: 'background 0.15s',
          }}>
            {!dismissed && '✓'}
          </div>
        </div>
      </div>

      {/* What the step will do, narrated by the backend */}
      <div style={{
        fontSize: '13.5px', fontWeight: 600, marginTop: '11px', lineHeight: 1.45,
        color: dismissed ? 'var(--text-faint)' : 'var(--text)',
        textDecoration: dismissed ? 'line-through' : 'none',
      }}>
        {item.title}
      </div>

      {/* Editable fields */}
      {!dismissed && fields.length > 0 && (
        <div
          onClick={(e) => e.stopPropagation()}
          style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 5 }}
        >
          {fields.map(({ key, label }) => (
            <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
              <span style={{ fontFamily: mono, fontSize: 10, color: 'var(--text-faint)', textTransform: 'uppercase', letterSpacing: '0.06em', width: 68, flexShrink: 0 }}>
                {label}
              </span>
              <input
                value={properties[key] ?? ''}
                onChange={(e) => onEdit(key, e.target.value)}
                onClick={(e) => e.stopPropagation()}
                style={{
                  flex: 1, height: 26, padding: '0 8px',
                  fontFamily: mono, fontSize: 12,
                  background: 'var(--surface-2)', color: 'var(--text)',
                  border: '1px solid var(--border-strong)', borderRadius: 6,
                  outline: 'none', boxSizing: 'border-box',
                }}
                onFocus={(e) => { e.target.style.borderColor = 'var(--accent)'; }}
                onBlur={(e) => { e.target.style.borderColor = 'var(--border-strong)'; onCommitEdit?.(); }}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
