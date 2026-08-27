import { useEffect, useMemo, useRef, useState } from 'react';

const mono = "'JetBrains Mono', monospace";

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'];
// Monday first: the week a spreadsheet's dates are grouped by starts on a Monday nearly everywhere
// this will run, and a calendar that disagrees with the sheet is a calendar people misread.
const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];

/** ISO `yyyy-mm-dd` built from local parts — `toISOString` would shift the day across a timezone. */
function iso(date) {
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function parse(value) {
  if (!value) return null;
  const [y, m, d] = value.split('-').map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d);
}

function label(value) {
  const date = parse(value);
  if (!date) return '';
  return `${`${date.getDate()}`.padStart(2, '0')} ${MONTHS[date.getMonth()].slice(0, 3)} ${date.getFullYear()}`;
}

/** The grid for a month, padded so the first row starts on the right weekday. */
function grid(year, month) {
  const first = new Date(year, month, 1);
  // getDay() is Sunday-first; shift it so Monday is 0.
  const lead = (first.getDay() + 6) % 7;
  const days = new Date(year, month + 1, 0).getDate();

  const cells = Array.from({ length: lead }, () => null);
  for (let d = 1; d <= days; d++) cells.push(new Date(year, month, d));
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

/**
 * A date field built from the app's own tokens.
 *
 * The native `<input type="date">` this replaces looked like a control from a different program:
 * its own font, its own calendar, its own placeholder in whatever the browser's locale happened to
 * be — the field read `дд.мм.гггг` on a Russian browser in an otherwise English interface. It also
 * cannot be styled in any meaningful way, so matching it to the rest was never an option.
 */
export default function DatePicker({ value, onChange, min, max, placeholder = 'Any date' }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef(null);

  const selected = parse(value);
  const [cursor, setCursor] = useState(() => selected ?? new Date());

  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => { if (anchor.current && !anchor.current.contains(e.target)) setOpen(false); };
    const escape = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', close, true);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close, true);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const cells = useMemo(() => grid(cursor.getFullYear(), cursor.getMonth()), [cursor]);
  const today = iso(new Date());

  const outOfRange = (date) => {
    const d = iso(date);
    return (min && d < min) || (max && d > max);
  };

  const step = (months) => setCursor(c => new Date(c.getFullYear(), c.getMonth() + months, 1));

  const pick = (date) => {
    onChange(iso(date));
    setOpen(false);
  };

  const navButton = {
    width: 26, height: 26, borderRadius: 7, border: '1px solid var(--border-strong)',
    background: 'transparent', color: 'var(--text-dim)', cursor: 'pointer',
    fontFamily: 'inherit', fontSize: 12, lineHeight: 1,
  };

  return (
    <div ref={anchor} style={{ position: 'relative' }}>
      <button
        type="button"
        onClick={() => {
          // Opened on the chosen month rather than on today: what is being adjusted is
          // usually near what is already picked. Done on the click rather than in an
          // effect, because nothing outside React needs synchronising here.
          if (!open && selected) setCursor(selected);
          setOpen(o => !o);
        }}
        style={{
          height: 34, minWidth: 132, padding: '0 10px', borderRadius: 8,
          border: '1px solid var(--border-strong)', background: 'var(--surface-2)',
          color: value ? 'var(--text)' : 'var(--text-faint)',
          fontFamily: mono, fontSize: 12.5, cursor: 'pointer',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
        }}
      >
        <span>{value ? label(value) : placeholder}</span>
        <span style={{ fontSize: 11, color: 'var(--text-faint)' }}>▦</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, marginTop: 5, zIndex: 30,
          background: 'var(--surface)', border: '1px solid var(--border-strong)',
          borderRadius: 12, boxShadow: '0 12px 34px var(--shadow)', padding: 12, width: 250,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
            <button type="button" onClick={() => step(-1)} style={navButton} aria-label="Previous month">‹</button>
            <span style={{ fontSize: 13, fontWeight: 600 }}>
              {MONTHS[cursor.getMonth()]} {cursor.getFullYear()}
            </span>
            <button type="button" onClick={() => step(1)} style={navButton} aria-label="Next month">›</button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, marginBottom: 4 }}>
            {WEEKDAYS.map(day => (
              <div key={day} style={{ textAlign: 'center', fontFamily: mono, fontSize: 10, color: 'var(--text-faint)', padding: '2px 0' }}>
                {day}
              </div>
            ))}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2 }}>
            {cells.map((date, i) => {
              if (!date) return <div key={`pad-${i}`} />;
              const d = iso(date);
              const isSelected = value === d;
              const disabled = outOfRange(date);
              return (
                <button
                  key={d}
                  type="button"
                  disabled={disabled}
                  onClick={() => pick(date)}
                  style={{
                    height: 28, borderRadius: 7, border: 'none', fontFamily: mono, fontSize: 12,
                    cursor: disabled ? 'default' : 'pointer',
                    background: isSelected ? 'var(--accent)' : 'transparent',
                    color: isSelected ? 'var(--on-accent)' : disabled ? 'var(--text-faint)' : 'var(--text)',
                    opacity: disabled ? 0.35 : 1,
                    // Today is outlined rather than filled, so it cannot be mistaken for the choice.
                    outline: !isSelected && d === today ? '1px solid var(--border-strong)' : 'none',
                    outlineOffset: -1,
                  }}
                >
                  {date.getDate()}
                </button>
              );
            })}
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 10, paddingTop: 9, borderTop: '1px solid var(--border)' }}>
            <button
              type="button"
              onClick={() => { onChange(''); setOpen(false); }}
              style={{ ...navButton, width: 'auto', padding: '0 9px', fontSize: 12 }}
            >
              Clear
            </button>
            <button
              type="button"
              onClick={() => pick(new Date())}
              disabled={outOfRange(new Date())}
              style={{ ...navButton, width: 'auto', padding: '0 9px', fontSize: 12, opacity: outOfRange(new Date()) ? 0.4 : 1 }}
            >
              Today
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
