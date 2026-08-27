const box = {
  height: 34, padding: '0 8px', borderRadius: 8,
  border: '1px solid var(--border-strong)', background: 'var(--surface-2)',
  color: 'var(--text)', fontFamily: 'inherit', fontSize: 13, boxSizing: 'border-box',
};

/**
 * Two dates that mean one range.
 *
 * `date` rather than `datetime-local`: nobody filters a history to the minute, and the seven extra
 * characters are seven more ways to mistype. The ends are made inclusive by the caller, because
 * "to 20 August" plainly includes the twentieth.
 */
export default function DateRange({ label, from, to, onChange }) {
  return (
    <div>
      {label && (
        <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>{label}</span>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <input
          type="date"
          value={from ?? ''}
          max={to || undefined}
          onChange={e => onChange({ from: e.target.value, to })}
          style={box}
        />
        <span style={{ color: 'var(--text-faint)', fontSize: 12 }}>–</span>
        <input
          type="date"
          value={to ?? ''}
          min={from || undefined}
          onChange={e => onChange({ from, to: e.target.value })}
          style={box}
        />
      </div>
    </div>
  );
}
