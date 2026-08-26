import * as XLSX from 'xlsx';

const isNumeric = (v) =>
  typeof v === 'number' ||
  (typeof v === 'string' && v !== '' && !isNaN(Number(String(v).replace(/[$,%\s]/g, ''))));

const formatCell = (v) => {
  if (v === null || v === undefined || v === '') return '';
  if (typeof v === 'number') {
    return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2);
  }
  return String(v);
};

const toNum = (v) => {
  if (typeof v === 'number') return v;
  const n = parseFloat(String(v ?? '').replace(/[$,%\s]/g, ''));
  return isNaN(n) ? 0 : n;
};

function parseRgb(rgb) {
  if (!rgb || typeof rgb !== 'string') return null;
  const hex = rgb.length === 8 ? rgb.slice(2) : rgb;
  if (hex.length !== 6) return null;
  if (hex === 'FFFFFF' || hex === 'ffffff' || hex === '000000') return null;
  return `#${hex}`;
}

function extractCellStyle(ws, r, c) {
  const cell = ws[XLSX.utils.encode_cell({ r, c })];
  if (!cell?.s || typeof cell.s === 'number') return null;

  const s = cell.s;
  const css = {};

  // SheetJS 0.18.x flat style: s.fgColor, s.bold, s.sz, s.color (not nested)
  const bg = parseRgb(s.fgColor?.rgb ?? s.bgColor?.rgb);
  if (bg) css.background = bg;

  if (s.bold) css.fontWeight = 'bold';
  if (s.italic) css.fontStyle = 'italic';
  if (s.underline) css.textDecoration = 'underline';
  const fontColor = parseRgb(s.color?.rgb);
  if (fontColor) css.color = fontColor;
  if (s.sz && s.sz !== 10 && s.sz !== 11) css.fontSize = `${Math.round(s.sz * 1.1)}px`;

  if (s.alignment?.horizontal && s.alignment.horizontal !== 'general') {
    const h = s.alignment.horizontal;
    if (h === 'right') css.justifyContent = 'flex-end';
    else if (h === 'center') css.justifyContent = 'center';
  }

  return Object.keys(css).length > 0 ? css : null;
}

/**
 * The hard ceiling on how many data rows the preview ever materialises. A 35 000-row sheet builds
 * a styled object per cell, so the whole workbook would be turned into a quarter of a million
 * objects before a single row could render.
 */
export const PREVIEW_ROW_CAP = 100;

// sheet_to_json indexes from the used range's top-left, not from A1 — a sheet whose data starts
// at, say, C3 would otherwise resolve every chart range three rows and two columns off.
function sheetRange(ws) {
  try {
    const ref = ws?.['!ref'];
    return ref ? XLSX.utils.decode_range(ref) : null;
  } catch {
    return null;
  }
}

/**
 * Reads at most `cap` data rows, and reports how many the sheet really has so the UI can say it is
 * showing a slice. The cap goes into sheet_to_json's `range` — the rows past it are never built,
 * which is the whole point; slicing afterwards would already have cost the memory.
 */
function parseSheet(ws, cap) {
  const used = sheetRange(ws);
  const origin = used ? used.s : { r: 0, c: 0 };
  // Header row aside, this is what the sheet holds — the number the row counter reports.
  const totalRows = used ? Math.max(0, used.e.r - used.s.r) : 0;

  const capped = used
    ? { s: { ...used.s }, e: { r: Math.min(used.e.r, used.s.r + cap), c: used.e.c } }
    : undefined;
  const raw = XLSX.utils.sheet_to_json(ws, { header: 1, defval: '', range: capped });
  if (!raw || raw.length < 2) return { rows: [], values: raw ?? [], origin, chartData: null, totalRows };

  const [header, ...data] = raw;
  const colCount = Math.max(header.length, ...data.map((r) => r.length));

  const numericCols = new Set();
  data.forEach((row) => {
    row.forEach((cell, ci) => {
      if (ci > 0 && isNumeric(cell)) numericCols.add(ci);
    });
  });

  const c = (t, s = '', a = 'left', f = null, css = null) => ({ t: String(t ?? ''), s, a, f, css });
  const formula = (ri, ci) => {
    const cell = ws[XLSX.utils.encode_cell({ r: origin.r + ri, c: origin.c + ci })];
    return cell?.f ? `=${cell.f}` : null;
  };
  const style = (ri, ci) => extractCellStyle(ws, origin.r + ri, origin.c + ci);

  const rows = [
    header.map((h, ci) => c(h, 'head', numericCols.has(ci) ? 'right' : 'left', formula(0, ci), style(0, ci))),
    ...data.map((row, di) =>
      Array.from({ length: colCount }, (_, ci) => {
        const val = row[ci] ?? '';
        const isNum = numericCols.has(ci);
        return c(formatCell(val), '', isNum ? 'right' : 'left', formula(di + 1, ci), style(di + 1, ci));
      })
    ),
  ];

  const chartData = buildChartData(header, data, numericCols);
  return { rows, values: raw, origin, chartData, totalRows };
}

// ── Column widths ───────────────────────────────────────────────────────────
// Lives here rather than in the grid because it is measured on exactly the display strings
// parseSheet produces, and because it has to be verifiable against a real workbook without React.

const CHAR_PX = 7.05;   // one JetBrains Mono glyph at 12px
const CELL_PAD = 22;    // the cell's own left/right padding, plus its border
export const MIN_COL = 80;
export const MAX_COL = 320;

/**
 * A width per column, from the longest of the header and the rows passed in. The ceiling is the
 * point of it: an 800-character paragraph would otherwise size its column to thousands of pixels
 * and push every column after it off-screen.
 */
export function measureColumnWidths(rows) {
  const colCount = rows?.[0]?.length ?? 0;
  return Array.from({ length: colCount }, (_, ci) => {
    let longest = 0;
    for (const row of rows) longest = Math.max(longest, (row[ci]?.t ?? '').length);
    return Math.min(MAX_COL, Math.max(MIN_COL, Math.round(longest * CHAR_PX + CELL_PAD)));
  });
}

export function applyEditsToBuffer(buffer, edits, sheetRenames) {
  const hasEdits = edits && edits.length > 0;
  const hasRenames = sheetRenames && Object.keys(sheetRenames).length > 0;
  if (!hasEdits && !hasRenames) return buffer;

  const wb = XLSX.read(buffer, { type: 'array', cellStyles: true });

  if (hasRenames) {
    Object.entries(sheetRenames).forEach(([idxStr, newName]) => {
      const idx = Number(idxStr);
      const oldName = wb.SheetNames[idx];
      const trimmed = String(newName ?? '').trim();
      if (!oldName || !trimmed || oldName === trimmed) return;
      const ws = wb.Sheets[oldName];
      delete wb.Sheets[oldName];
      wb.Sheets[trimmed] = ws;
      wb.SheetNames[idx] = trimmed;
    });
  }

  (edits ?? []).forEach(({ sheetIdx, ri, ci, value }) => {
    const sheetName = wb.SheetNames[sheetIdx];
    if (!sheetName) return;
    const ws = wb.Sheets[sheetName];
    const cellRef = XLSX.utils.encode_cell({ r: ri, c: ci });
    const existing = ws[cellRef] || {};
    const trimmed = String(value).trim();
    const num = Number(trimmed.replace(/[,$\s]/g, ''));
    const isNum = trimmed !== '' && !isNaN(num);
    const newCell = { ...existing, t: isNum ? 'n' : 's', v: isNum ? num : trimmed, w: trimmed };
    delete newCell.f;
    ws[cellRef] = newCell;
  });
  return XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
}

// Converts xlsx ArrayBuffer → { sheets: [{ name, rows, values, origin, chartData, totalRows }] }
// `values` is the raw grid, kept because a chart's series must be resolved from real numbers
// rather than from the display strings in `rows`.
// `rows` is a preview: at most PREVIEW_ROW_CAP data rows, with `totalRows` saying how many the
// sheet actually has. A caller may ask for fewer, never for more.
export function parseWorkbook(buffer, cap = PREVIEW_ROW_CAP) {
  const wb = XLSX.read(buffer, { type: 'array', cellStyles: true });
  const limit = Math.max(1, Math.min(PREVIEW_ROW_CAP, Math.floor(cap) || PREVIEW_ROW_CAP));

  const sheets = wb.SheetNames.map((name) => ({ name, ...parseSheet(wb.Sheets[name], limit) }));

  return { sheets };
}

// ── Real charts ─────────────────────────────────────────────────────────────
// A workbook's own charts are invisible to SheetJS, so the server sends their definitions and
// the series ranges are resolved here, against the sheets already parsed above.

/**
 * `Sheet1!$A$2:$C$6` → { sheet, r1, c1, r2, c2 }, zero-based and inclusive.
 * Returns null for anything it cannot read rather than a half-parsed range — a wrong range would
 * draw a confident chart of the wrong cells.
 */
export function parseA1Range(ref) {
  if (typeof ref !== 'string') return null;
  const text = ref.trim();
  if (!text) return null;

  // A quoted sheet name may itself contain '!' and doubled quotes ('Bob''s Q3!'), so the quoted
  // form is matched whole before falling back to splitting on the last '!'.
  let sheet = null;
  let cells = text;
  const quoted = /^'((?:[^']|'')*)'!(.+)$/.exec(text);
  if (quoted) {
    sheet = quoted[1].replace(/''/g, "'");
    cells = quoted[2];
  } else if (text.includes('!')) {
    const split = text.lastIndexOf('!');
    sheet = text.slice(0, split);
    cells = text.slice(split + 1);
  }
  if (sheet !== null && sheet.trim() === '') return null;

  const ends = cells.split(':');
  if (ends.length > 2) return null;
  const from = parseA1Cell(ends[0]);
  const to = ends.length === 2 ? parseA1Cell(ends[1]) : from;
  if (!from || !to) return null;

  return {
    sheet: sheet === null ? null : sheet.trim(),
    r1: Math.min(from.r, to.r), c1: Math.min(from.c, to.c),
    r2: Math.max(from.r, to.r), c2: Math.max(from.c, to.c),
  };
}

function parseA1Cell(text) {
  const m = /^\$?([A-Za-z]{1,3})\$?(\d{1,7})$/.exec(String(text ?? '').trim());
  if (!m) return null;
  let col = 0;
  for (const ch of m[1].toUpperCase()) col = col * 26 + (ch.charCodeAt(0) - 64);
  const row = Number(m[2]);
  return row < 1 ? null : { r: row - 1, c: col - 1 };
}

/**
 * Reads the cells an A1 range covers out of the parsed sheets, row by row. Null means "cannot be
 * resolved" — a sheet that has been renamed or deleted, a range past the end of the data, or a
 * reference into another workbook.
 */
export function resolveRange(sheets, ref, fallbackSheetName) {
  const range = parseA1Range(ref);
  if (!range) return null;

  const wanted = range.sheet ?? fallbackSheetName;
  const sheet = (sheets ?? []).find((s) => s.name === wanted);
  if (!sheet || !Array.isArray(sheet.values)) return null;

  const cells = [];
  let found = false;
  for (let r = range.r1; r <= range.r2; r++) {
    const row = sheet.values[r - sheet.origin.r];
    for (let c = range.c1; c <= range.c2; c++) {
      const value = row ? row[c - sheet.origin.c] : undefined;
      if (value !== undefined && value !== null && value !== '') found = true;
      cells.push(value);
    }
  }
  return found ? cells : null;
}

/**
 * A server-side chart definition → what ChartPreview draws, or null if any series is unresolvable.
 * All-or-nothing on purpose: half a chart is a lie, and the caller has an honest fallback.
 */
export function resolveChartDefinition(definition, sheets) {
  const definitionSeries = definition?.series ?? [];
  if (definitionSeries.length === 0) return null;

  const home = definition.sheetName;
  let labels = null;
  const series = [];

  for (const s of definitionSeries) {
    const values = resolveRange(sheets, s.valuesRef, home);
    if (!values) return null;
    if (!labels) {
      const categories = resolveRange(sheets, s.categoriesRef, home);
      labels = categories
        ? categories.map((v) => formatCell(v))
        : values.map((_, i) => String(i + 1));
    }
    series.push({ name: s.name || definition.title || `Series ${series.length + 1}`, values: values.map(toNum) });
  }

  // Category and value ranges of different lengths happen; trim rather than invent points.
  const length = Math.min(labels.length, ...series.map((s) => s.values.length));
  if (length === 0) return null;

  return {
    type: definition.type ?? 'unknown',
    title: definition.title && definition.title !== '(untitled)' ? definition.title : null,
    xLabels: labels.slice(0, length),
    series: series.map((s) => ({ name: s.name, values: s.values.slice(0, length) })),
  };
}

function buildChartData(header, data, numericCols) {
  if (numericCols.size === 0 || data.length < 2) return null;

  const numericColIndexes = [...numericCols].slice(0, 6);
  const dataRows = data.filter((row) => row[0] !== '' && row[0] !== undefined).slice(0, 12);

  if (numericColIndexes.length === 1) {
    const ci = numericColIndexes[0];
    return {
      xLabels: dataRows.map((row) => String(row[0] ?? '')),
      series: [{ name: String(header[ci] ?? 'Value'), values: dataRows.map((row) => toNum(row[ci])) }],
    };
  }

  const xLabels = numericColIndexes.map((ci) => String(header[ci] ?? `Col ${ci}`));
  const series = dataRows
    .map((row) => ({
      name: String(row[0] ?? ''),
      values: numericColIndexes.map((ci) => toNum(row[ci])),
    }))
    .filter((s) => s.values.some((v) => v > 0));

  if (series.length === 0) return null;
  return { xLabels, series };
}
