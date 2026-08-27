const mono = "'JetBrains Mono', monospace";

const TONES = {
  neutral: ['var(--surface-2)', 'var(--text-dim)'],
  good: ['var(--accent-soft)', 'var(--accent-text)'],
  warn: ['var(--warn-bg)', 'var(--warn)'],
  bad: ['var(--del-bg)', 'var(--del)'],
};

/** A small status pill. Tone is named by meaning, not by colour, so the palette can move. */
export default function Badge({ tone = 'neutral', children, style }) {
  const [background, color] = TONES[tone] ?? TONES.neutral;
  return (
    <span style={{ display: 'inline-block', padding: '2px 8px', borderRadius: 6, background, color, fontFamily: mono, fontSize: 11, letterSpacing: '0.03em', whiteSpace: 'nowrap', ...style }}>
      {children}
    </span>
  );
}
