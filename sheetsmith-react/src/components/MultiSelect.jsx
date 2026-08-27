import { useEffect, useRef, useState } from 'react';
import Checkbox from './Checkbox.jsx';

const mono = "'JetBrains Mono', monospace";

/**
 * Pick several of a short list.
 *
 * A popover rather than a native `<select multiple>`, which is unusable without knowing that
 * ctrl-click is what selects a second item — and silently drops the first selection when someone
 * clicks normally.
 */
export default function MultiSelect({ label, options, value = [], onChange, placeholder = 'Any' }) {
  const [open, setOpen] = useState(false);
  const box = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => { if (box.current && !box.current.contains(e.target)) setOpen(false); };
    // Capture, so a click that also does something else still closes this first.
    document.addEventListener('mousedown', close, true);
    return () => document.removeEventListener('mousedown', close, true);
  }, [open]);

  const toggle = (optionValue) => {
    onChange(value.includes(optionValue)
      ? value.filter(v => v !== optionValue)
      : [...value, optionValue]);
  };

  const chosen = options.filter(o => value.includes(o.value));
  const summary = chosen.length === 0
    ? placeholder
    : chosen.length <= 2
      ? chosen.map(o => o.label).join(', ')
      : `${chosen.length} selected`;

  return (
    <div ref={box} style={{ position: 'relative', minWidth: 150 }}>
      {label && (
        <span style={{ display: 'block', fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 5 }}>{label}</span>
      )}
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        style={{
          width: '100%', height: 34, padding: '0 10px', borderRadius: 8,
          border: '1px solid var(--border-strong)', background: 'var(--surface-2)',
          color: chosen.length ? 'var(--text)' : 'var(--text-faint)',
          fontFamily: 'inherit', fontSize: 13, cursor: 'pointer', textAlign: 'left',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
          overflow: 'hidden', whiteSpace: 'nowrap',
        }}
      >
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{summary}</span>
        <span style={{ fontSize: 10, color: 'var(--text-faint)', flexShrink: 0 }}>▾</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, marginTop: 4, zIndex: 20,
          minWidth: '100%', maxHeight: 260, overflowY: 'auto',
          background: 'var(--surface)', border: '1px solid var(--border-strong)',
          borderRadius: 10, boxShadow: '0 10px 30px var(--shadow)', padding: 4,
        }}>
          {options.length === 0 && (
            <div style={{ padding: '10px 12px', fontSize: 12.5, color: 'var(--text-faint)' }}>Nothing to choose</div>
          )}
          {options.map(option => (
            <Checkbox
              key={option.value}
              checked={value.includes(option.value)}
              onChange={() => toggle(option.value)}
              label={<span style={{ fontFamily: option.mono ? mono : 'inherit' }}>{option.label}</span>}
              style={{ display: 'flex', padding: '7px 10px', borderRadius: 6, whiteSpace: 'nowrap' }}
            />
          ))}
        </div>
      )}
    </div>
  );
}
