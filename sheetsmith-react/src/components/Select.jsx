import { inputStyle } from './inputStyle.js';

/** A labelled select, sharing the input's box so the two line up in a row. */
export default function Select({ label, options = [], style, ...rest }) {
  return (
    <label style={{ display: 'block', ...style }}>
      {label && (
        <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>
          {label}
        </span>
      )}
      <select style={{ ...inputStyle, cursor: 'pointer' }} {...rest}>
        {options.map(option => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    </label>
  );
}
