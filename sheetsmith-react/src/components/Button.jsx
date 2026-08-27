const BASE = {
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 7,
  fontFamily: 'inherit', fontWeight: 500, whiteSpace: 'nowrap', boxSizing: 'border-box',
};

const SIZES = {
  sm: { height: 30, padding: '0 12px', borderRadius: 8, fontSize: 12.5 },
  md: { height: 32, padding: '0 14px', borderRadius: 8, fontSize: 13 },
  lg: { height: 40, padding: '0 18px', borderRadius: 9, fontSize: 14, fontWeight: 600 },
};

// Every variant is a pair of theme tokens, never a literal colour: that is what keeps a new button
// correct in both themes without anybody remembering to check the dark one.
const VARIANTS = {
  primary: { background: 'var(--accent)', color: 'var(--on-accent)', border: 'none' },
  secondary: { background: 'transparent', color: 'var(--text-dim)', border: '1px solid var(--border-strong)' },
  danger: { background: 'transparent', color: 'var(--del)', border: '1px solid var(--del)' },
  ghost: { background: 'transparent', color: 'var(--text-dim)', border: 'none' },
};

/**
 * The button, in the four shapes this app actually uses.
 *
 * It exists because the same six style properties were written out at thirty-eight call sites, each
 * free to drift by a pixel or a token — and several already had.
 */
export default function Button({
  variant = 'secondary',
  size = 'md',
  block = false,
  disabled = false,
  style,
  children,
  ...rest
}) {
  return (
    <button
      disabled={disabled}
      style={{
        ...BASE,
        ...SIZES[size],
        ...VARIANTS[variant],
        width: block ? '100%' : undefined,
        // Disabled says so by looking unavailable and by not offering a pointer, rather than by
        // silently doing nothing when clicked.
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.55 : 1,
        ...style,
      }}
      {...rest}
    >
      {children}
    </button>
  );
}
