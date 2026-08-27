import { inputStyle } from './inputStyle.js';

const mono = "'JetBrains Mono', monospace";

/**
 * A labelled input. The label is a real `<label>` wrapping its control, so clicking the text
 * focuses the field — the version this replaces was a styled `div` and did not.
 */
export default function Field({ label, hint, error, monospace = false, style, ...rest }) {
  return (
    <label style={{ display: 'block', marginBottom: 14, ...style }}>
      {label && (
        <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>
          {label}
        </span>
      )}
      <input
        style={{
          ...inputStyle,
          fontFamily: monospace ? mono : 'inherit',
          borderColor: error ? 'var(--del)' : 'var(--border-strong)',
        }}
        {...rest}
      />
      {(error || hint) && (
        <span style={{ display: 'block', fontSize: 12, marginTop: 5, color: error ? 'var(--del)' : 'var(--text-faint)' }}>
          {error || hint}
        </span>
      )}
    </label>
  );
}
