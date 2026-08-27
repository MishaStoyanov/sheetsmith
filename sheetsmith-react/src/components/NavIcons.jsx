/**
 * The left menu's icons, drawn rather than typed.
 *
 * These were text glyphs — ▦ ⧗ ◔ ◍ ⚙ — which is fine until the machine running this does not have
 * a font with them. Then they fall back to whatever face does, arriving at a different weight and
 * baseline, or as an empty box. For something people self-host on whatever they happen to run, that
 * is not a risk worth taking for a row of five shapes.
 *
 * It also fixed the one that never worked: there is no glyph in Unicode that reads as "people"
 * without being an emoji, so Users was a circle with a dot in it — the only icon in the row that
 * depicted nothing.
 *
 * One geometry throughout: a 16-unit square, 1.5 of stroke, round caps and joins, and colour taken
 * from the text around it so an icon never carries a colour of its own.
 */
function Glyph({ children }) {
  return (
    <svg
      width="16" height="16" viewBox="0 0 16 16"
      fill="none" stroke="currentColor" strokeWidth="1.5"
      strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" focusable="false"
    >
      {children}
    </svg>
  );
}

/** A sheet, divided. What the whole application is pointed at. */
export function SheetIcon() {
  return (
    <Glyph>
      <rect x="2" y="2.5" width="12" height="11" rx="1.6" />
      <path d="M2 6.6h12" />
      <path d="M6.4 6.6v6.9" />
    </Glyph>
  );
}

/** A clock. History is a question about when, so the icon is about when. */
export function ClockIcon() {
  return (
    <Glyph>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 4.6V8l2.4 1.5" />
    </Glyph>
  );
}

/** A ring with one part filled: the shape the analytics screen is mostly made of. */
export function ChartIcon() {
  return (
    <Glyph>
      <circle cx="8" cy="8" r="6" />
      {/* Filled rather than outlined, so at this size it still reads as a share of a whole. */}
      <path d="M8 2a6 6 0 0 1 6 6H8Z" fill="currentColor" stroke="none" />
    </Glyph>
  );
}

/** Two figures, because the menu item is people and there are usually more than one. */
export function UsersIcon() {
  return (
    <Glyph>
      <circle cx="6.1" cy="5.1" r="2.6" />
      <path d="M1.4 13.8v-1a3 3 0 0 1 3-3h3.4a3 3 0 0 1 3 3v1" />
      {/* The second figure is only ever partly drawn: a whole one would crowd the square and the
          half is enough to say "and others". */}
      <path d="M10.7 2.9a2.6 2.6 0 0 1 0 4.5" />
      <path d="M14.6 13.8v-1a3 3 0 0 0-2.2-2.9" />
    </Glyph>
  );
}

/**
 * Sliders rather than a cog. A cog at sixteen pixels with a stroke this thick is a circle with
 * some fuzz round it — the teeth are shorter than the line drawing them. Sliders say the same
 * thing and survive the size.
 */
export function SettingsIcon() {
  return (
    <Glyph>
      {/* The line stops either side of the knob rather than being hidden by a filled one. A knob
          painted in the surface colour is a knob that shows the wrong colour the moment the row
          behind it is highlighted. */}
      <path d="M2 5.2h6.1M11.9 5.2H14" />
      <circle cx="10" cy="5.2" r="1.8" />
      <path d="M2 10.8h2.1M7.9 10.8H14" />
      <circle cx="6" cy="10.8" r="1.8" />
    </Glyph>
  );
}

/** The menu's own handle, pointing the way it will move. */
export function ChevronIcon({ pointing }) {
  return (
    <Glyph>
      {pointing === 'left' ? <path d="M10 3.5 5.5 8l4.5 4.5" /> : <path d="M6 3.5 10.5 8 6 12.5" />}
    </Glyph>
  );
}
