const mono = "'JetBrains Mono', monospace";

/**
 * Phrasings you have used before, offered back as one-click fills.
 *
 * Only ever your own — a prompt is somebody describing their own data in their own words, so these
 * never appear on a shared screen. That rule is enforced by the endpoint; this component simply has
 * no way to ask for anyone else's.
 *
 * Nothing is shown until a phrasing has been used more than once, so a fresh instance is not
 * decorated with a panel offering the single thing its owner has ever typed.
 */
export default function PromptRecall({ prompts, onPick, disabled }) {
  if (!prompts.length) {
    return null;
  }

  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap' }}>
      <span style={{ fontFamily: mono, fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-faint)', paddingTop: 6, flexShrink: 0 }}>
        You often ask
      </span>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
        {prompts.map(prompt => (
          <button
            key={prompt.text}
            type="button"
            onClick={() => onPick(prompt.text)}
            disabled={disabled}
            // The full text on hover: the chip is shortened to fit a row, and the decision about
            // which half of a sentence matters belongs to whoever wrote it.
            title={`${prompt.text}\n\nUsed ${prompt.uses} times`}
            style={{
              maxWidth: 320, height: 28, padding: '0 11px', borderRadius: 8,
              border: '1px solid var(--border)', background: 'var(--surface-2)',
              color: disabled ? 'var(--text-faint)' : 'var(--text-dim)',
              fontFamily: 'inherit', fontSize: 12.5,
              cursor: disabled ? 'default' : 'pointer',
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              display: 'flex', alignItems: 'center', gap: 7,
            }}
          >
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{prompt.text}</span>
            <span style={{ fontFamily: mono, fontSize: 10.5, color: 'var(--text-faint)', flexShrink: 0 }}>
              ×{prompt.uses}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}
