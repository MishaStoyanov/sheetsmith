/**
 * A checkbox in the app's own colours.
 *
 * The native one is painted by the operating system: a white box with a blue tick on Windows, a
 * different white box on macOS, and neither has any relationship to the rest of the interface. It
 * cannot be restyled, only replaced.
 *
 * The real input is still here, just invisible — so the label, the keyboard, focus order, form
 * submission and screen readers all keep working. Only the painting is ours.
 *
 * Checked reuses the pair the left menu already uses for the active tab: a soft accent fill with an
 * accent border, and the tick itself in accent. That is what "in the project's style" resolves to
 * without inventing a third meaning for green.
 */
export default function Checkbox({ checked, onChange, label, disabled = false, style }) {
  return (
    <label
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 8,
        fontSize: 13, color: 'var(--text-dim)',
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.55 : 1,
        userSelect: 'none',
        ...style,
      }}
    >
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={e => onChange?.(e.target.checked)}
        // Not `display: none`: that would take it out of the tab order and out of the
        // accessibility tree along with it.
        style={{ position: 'absolute', opacity: 0, width: 0, height: 0, margin: 0 }}
      />
      <span
        aria-hidden="true"
        style={{
          width: 16, height: 16, flexShrink: 0, borderRadius: 5,
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          background: checked ? 'var(--accent-soft)' : 'var(--surface-2)',
          border: `1.5px solid ${checked ? 'var(--accent)' : 'var(--border-strong)'}`,
          transition: 'background 0.12s, border-color 0.12s',
        }}
      >
        {checked && (
          <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
            <path
              d="M2.5 6.2 L4.8 8.5 L9.5 3.5"
              stroke="var(--accent-text)"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
      {label}
    </label>
  );
}
