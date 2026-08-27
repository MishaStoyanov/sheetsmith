import DatePicker from './DatePicker.jsx';

/**
 * Two dates that mean one range.
 *
 * Each end bounds the other, so the pair cannot be put the wrong way round in the first place —
 * which is better than validating it afterwards and explaining the mistake.
 */
export default function DateRange({ label, from, to, onChange }) {
  return (
    <div>
      {label && (
        <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>{label}</span>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <DatePicker
          value={from}
          max={to || undefined}
          placeholder="From"
          onChange={next => onChange({ from: next, to })}
        />
        <span style={{ color: 'var(--text-faint)', fontSize: 12 }}>–</span>
        <DatePicker
          value={to}
          min={from || undefined}
          placeholder="To"
          onChange={next => onChange({ from, to: next })}
        />
      </div>
    </div>
  );
}
