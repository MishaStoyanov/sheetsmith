import { useState, useEffect, useMemo, useRef } from 'react';
import ChartPreview from './ChartPreview.jsx';
import { resolveChartDefinition, measureColumnWidths, PREVIEW_ROW_CAP, MIN_COL } from './parseSheet.js';

// Shown under a chart we drew ourselves from the sheet's numbers, so nobody reads it as theirs.
const SYNTHESISED = 'Drawn from this sheet’s data — the chart in the file could not be read.';

const mono = "'JetBrains Mono', ui-monospace, monospace";

// How many data rows the grid shows. Never above the parse cap — those rows do not exist here.
const ROW_OPTIONS = [10, 25, 50, 100].filter((n) => n <= PREVIEW_ROW_CAP);
const DEFAULT_ROWS = 10;

// Column sizing beyond the measured widths (which parseSheet owns, and caps).
const MIN_DRAG = 60;
const MAX_DRAG = 1200;         // a width the user dragged to is theirs; only sanity-bounded
const GUTTER_W = 44;

const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, Math.round(n)));

/** A1-style column names, so a sheet wider than Z does not fall back to bare numbers. */
function columnName(index) {
  let name = '';
  for (let n = index; n >= 0; n = Math.floor(n / 26) - 1) {
    name = String.fromCharCode(65 + (n % 26)) + name;
  }
  return name;
}

function cellStyle(s = '', a = 'left', selected = false, css = null, edited = false) {
  const st = {
    height: '30px', padding: '0 10px', verticalAlign: 'middle',
    fontSize: '12px', color: 'var(--text)', textAlign: a,
    borderRight: '1px solid var(--border)', borderBottom: '1px solid var(--border)',
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
    background: 'var(--surface)', fontFamily: mono, fontWeight: 400,
    cursor: 'default', userSelect: 'none', boxSizing: 'border-box',
  };
  if (s === 'corner' || s === 'letter' || s === 'gutter') {
    st.background = 'var(--surface-2)'; st.color = 'var(--text-faint)'; st.fontSize = '11px'; st.textAlign = 'center';
  }
  if (s.includes('head')) { st.fontWeight = 600; st.color = 'var(--text)'; }
  if (s.includes('total')) { st.fontWeight = 600; st.background = 'var(--surface-2)'; st.borderTop = '1px solid var(--border-strong)'; }
  if (css) {
    // The workbook's own alignment arrives as flexbox, which a table cell cannot use.
    const { justifyContent, ...rest } = css;
    Object.assign(st, rest);
    if (justifyContent === 'flex-end') st.textAlign = 'right';
    if (justifyContent === 'center') st.textAlign = 'center';
  }
  if (edited) { st.background = 'var(--accent-soft)'; st.boxShadow = 'inset 2px 0 0 var(--accent)'; }
  if (selected && !edited) {
    st.boxShadow = 'inset 0 0 0 2px var(--accent)';
    st.background = s.includes('head') ? 'var(--surface-2)' : 'var(--accent-soft)';
    st.position = 'relative'; st.zIndex = 2;
  }
  return st;
}

function Grid({ rows, sheetIdx, editedKeys, onCellEdit, totalRows, theme, onWidth }) {
  const [selected, setSelected] = useState(null);
  const [editCell, setEditCell] = useState(null);  // { ri, ci }
  const [editValue, setEditValue] = useState('');
  const [visibleRows, setVisibleRows] = useState(DEFAULT_ROWS);
  // Widths the user dragged to, by column index. They outlive a re-render and the row dropdown.
  const [dragged, setDragged] = useState({});
  const [dragCol, setDragCol] = useState(null);
  const inputRef = useRef(null);
  const dragRef = useRef(null);

  useEffect(() => { if (editCell && inputRef.current) inputRef.current.focus(); }, [editCell]);

  const colCount = rows[0]?.length ?? 0;
  const letters = Array.from({ length: colCount }, (_, i) => columnName(i));

  // The rows already in memory, cut to what the dropdown asks for — changing it re-slices, it
  // never re-parses.
  const shown = useMemo(
    () => (rows.length > 0 ? [rows[0], ...rows.slice(1, visibleRows + 1)] : rows),
    [rows, visibleRows]
  );
  const shownData = Math.max(0, shown.length - 1);
  const total = totalRows ?? shownData;

  // The header plus the rows actually on screen decide the width — an 800-character paragraph
  // three thousand rows down cannot widen a column nobody is looking at.
  const measured = useMemo(() => measureColumnWidths(shown), [shown]);

  const widthOf = (ci) => dragged[ci] ?? measured[ci] ?? MIN_COL;
  const tableWidth = GUTTER_W + measured.reduce((sum, _, ci) => sum + widthOf(ci), 0);

  useEffect(() => { onWidth?.(tableWidth); }, [tableWidth, onWidth]);

  const startResize = (event, ci) => {
    event.preventDefault();
    event.stopPropagation();
    dragRef.current = { ci, startX: event.clientX, startWidth: widthOf(ci) };
    setDragCol(ci);
  };

  // Tracked on the window, not the handle: the pointer leaves a 7px strip the moment it moves.
  useEffect(() => {
    if (dragCol === null) return undefined;
    const onMove = (event) => {
      const drag = dragRef.current;
      if (!drag) return;
      const next = clamp(drag.startWidth + (event.clientX - drag.startX), MIN_DRAG, MAX_DRAG);
      setDragged((prev) => ({ ...prev, [drag.ci]: next }));
    };
    const stop = () => { dragRef.current = null; setDragCol(null); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', stop);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', stop);
    };
  }, [dragCol]);

  const activeCellRef = selected ? `${letters[selected.ci] ?? '?'}${selected.ri + 1}` : '';
  const activeCell = selected ? rows[selected.ri]?.[selected.ci] : null;
  const formulaBarContent = editCell
    ? editValue
    : (activeCell ? (activeCell.f ?? activeCell.t) : '');

  const commitEdit = (ri, ci, value) => {
    setEditCell(null);
    if (value.trim() !== '') onCellEdit(sheetIdx, ri, ci, value.trim());
  };

  const handleKeyDown = (e, ri, ci) => {
    if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); commitEdit(ri, ci, editValue); }
    if (e.key === 'Escape') { setEditCell(null); setEditValue(''); }
  };

  return (
    <div>
      {/* Formula bar */}
      <div style={{ display: 'flex', alignItems: 'center', border: '1px solid var(--border)', borderRadius: '8px', overflow: 'hidden', background: 'var(--surface)', marginBottom: 6 }}>
        <div style={{ fontFamily: mono, fontSize: '12px', color: 'var(--text-dim)', padding: '7px 0', borderRight: '1px solid var(--border)', background: 'var(--surface-2)', width: '52px', textAlign: 'center', flexShrink: 0 }}>
          {activeCellRef || ' '}
        </div>
        <div style={{ fontFamily: mono, fontSize: '12px', color: 'var(--text-faint)', padding: '7px 10px', borderRight: '1px solid var(--border)', flexShrink: 0, fontStyle: 'italic' }}>fx</div>
        <div style={{ fontFamily: mono, fontSize: '12.5px', color: formulaBarContent?.startsWith('=') ? 'var(--accent-text)' : 'var(--text)', padding: '7px 12px', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {formulaBarContent || ' '}
        </div>
        {editedKeys.size > 0 && (
          <div style={{ marginLeft: 'auto', padding: '0 12px', fontSize: 11, color: 'var(--accent-text)', fontFamily: mono, flexShrink: 0 }}>
            {editedKeys.size} edited
          </div>
        )}
      </div>

      {/* How much of the sheet to show — and how much of it there is */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        {total > DEFAULT_ROWS && (
          <select
            value={visibleRows}
            onChange={(e) => setVisibleRows(Number(e.target.value))}
            title="How many rows to show"
            style={{
              height: 26, padding: '0 6px', borderRadius: 7, border: '1px solid var(--border-strong)',
              background: 'var(--surface-2)', color: 'var(--text)', fontFamily: mono, fontSize: 12,
              cursor: 'pointer', outline: 'none', colorScheme: theme === 'light' ? 'light' : 'dark',
            }}
          >
            {ROW_OPTIONS.filter((n, i) => i === 0 || ROW_OPTIONS[i - 1] < total)
              .map((n) => <option key={n} value={n}>{n} rows</option>)}
          </select>
        )}
        <span
          style={{ fontFamily: mono, fontSize: 12, color: 'var(--text-dim)' }}
          title={total > PREVIEW_ROW_CAP
            ? `Only the first ${PREVIEW_ROW_CAP} rows are read into the preview — the whole sheet is still what gets edited and exported.`
            : undefined}
        >
          {shownData.toLocaleString()} of {total.toLocaleString()} row{total === 1 ? '' : 's'}
        </span>
      </div>

      {/* Grid — fixed layout, so one enormous cell can never widen its column off the screen */}
      <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: '10px', background: 'var(--surface)' }}>
        <table style={{ tableLayout: 'fixed', borderCollapse: 'collapse', width: tableWidth, minWidth: '100%' }}>
          <colgroup>
            <col style={{ width: GUTTER_W }} />
            {letters.map((L, ci) => <col key={L} style={{ width: widthOf(ci) }} />)}
          </colgroup>
          <thead>
            <tr>
              <th style={cellStyle('corner', 'center')} />
              {letters.map((L, ci) => (
                <th key={L} style={{ ...cellStyle('letter', 'center'), padding: 0, position: 'relative' }}>
                  {L}
                  <span
                    onMouseDown={(e) => startResize(e, ci)}
                    onDoubleClick={(e) => { e.stopPropagation(); setDragged((prev) => { const { [ci]: _drop, ...rest } = prev; return rest; }); }}
                    title="Drag to resize · double-click to fit"
                    style={{
                      position: 'absolute', top: 0, right: 0, width: 7, height: '100%',
                      cursor: 'col-resize', display: 'flex', justifyContent: 'center',
                    }}
                  >
                    <span style={{ width: 1, height: '100%', background: dragCol === ci ? 'var(--accent)' : 'var(--border-strong)' }} />
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((row, ri) => (
              <tr key={ri}>
                <td style={cellStyle('gutter', 'center')}>{ri + 1}</td>
                {row.map((cell, ci) => {
                  const key = `${sheetIdx},${ri},${ci}`;
                  const isEditing = editCell?.ri === ri && editCell?.ci === ci;
                  const isSelected = selected?.ri === ri && selected?.ci === ci;
                  const isEdited = editedKeys.has(key);
                  const isHeader = cell.s?.includes('head');
                  const hint = isHeader ? 'Double-click to rename column' : 'Double-click to edit';

                  if (isEditing) {
                    return (
                      <td key={ci} style={{ ...cellStyle(cell.s, cell.a, false, cell.css), padding: 0, overflow: 'visible' }}>
                        <input
                          ref={inputRef}
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={() => commitEdit(ri, ci, editValue)}
                          onKeyDown={(e) => handleKeyDown(e, ri, ci)}
                          style={{
                            width: '100%', height: '28px', border: '2px solid var(--accent)',
                            borderRadius: 0, background: 'var(--accent-soft)',
                            color: 'var(--text)', fontFamily: mono, fontSize: '12px',
                            padding: '0 8px', boxSizing: 'border-box', outline: 'none',
                          }}
                        />
                      </td>
                    );
                  }

                  return (
                    <td
                      key={ci}
                      style={cellStyle(cell.s, cell.a, isSelected && !isEdited, cell.css, isEdited)}
                      onClick={() => {
                        if (editCell) commitEdit(editCell.ri, editCell.ci, editValue);
                        setSelected((prev) => prev?.ri === ri && prev?.ci === ci ? null : { ri, ci });
                      }}
                      onDoubleClick={() => {
                        setSelected({ ri, ci });
                        setEditCell({ ri, ci });
                        setEditValue(cell.t);
                      }}
                      // Truncated text is only readable on hover, so the full value goes in the tooltip.
                      title={cell.t && cell.t.length > 12 ? cell.t : hint}
                    >
                      {cell.t}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function SheetGrid({ sheets, charts, hasChart, theme, cellEdits, onCellEdit, sheetRenames, onRenameSheet }) {
  const [activeTab, setActiveTab] = useState(0);
  const [renamingTab, setRenamingTab] = useState(null);
  const [renameValue, setRenameValue] = useState('');
  // What the grid says it needs. A wide sheet is allowed out of the narrow reading column rather
  // than being squeezed into it; `--page-w` is what App left free next to the chat panel.
  const [gridWidth, setGridWidth] = useState(0);

  if (!sheets || sheets.length === 0) return null;

  const room = `min(${gridWidth + 2}px, 1180px, calc(var(--page-w, 100vw) - 74px))`;
  const bleed = gridWidth > 760
    ? { width: room, marginLeft: `calc((100% - ${room}) / 2)` }
    : null;

  const dataSheet = sheets.find((s) => s.rows.length > 1);
  const sharedChartData = dataSheet?.chartData ?? null;
  // A chart belongs to the sheet it was drawn on — that is the tab it shows up under, whether it
  // came in with the upload or CREATE_CHART put it on a sheet of its own.
  const chartsOn = (name) => (charts ?? []).filter((c) => c.sheetName === name);

  const activeSheet = sheets[activeTab] ?? sheets[0];
  const isChartOnly = activeSheet.rows.length < 2;

  const activeCharts = chartsOn(activeSheet.name);
  const resolvedCharts = activeCharts.map((c) => resolveChartDefinition(c, sheets)).filter(Boolean);
  // Nothing readable, but we know a chart is there — better an honest stand-in than a blank tab.
  const fallbackData = isChartOnly ? sharedChartData : activeSheet.chartData;
  const showFallback = resolvedCharts.length === 0 && !!fallbackData
    && (activeCharts.length > 0 || (isChartOnly && hasChart));

  // Build a Set of edited cell keys for fast lookup: "sheetIdx,ri,ci"
  const editedKeys = new Set((cellEdits ?? []).map((e) => `${e.sheetIdx},${e.ri},${e.ci}`));

  // Merge edits into displayed rows for the active sheet
  const displayRows = activeSheet.rows.map((row, ri) =>
    row.map((cell, ci) => {
      const edit = (cellEdits ?? []).find((e) => e.sheetIdx === activeTab && e.ri === ri && e.ci === ci);
      return edit ? { ...cell, t: edit.value } : cell;
    })
  );

  const commitRename = (i) => {
    onRenameSheet?.(i, renameValue);
    setRenamingTab(null);
  };

  return (
    <div style={bleed ?? undefined}>
      <div style={{ display: 'flex', gap: 2, marginBottom: 8, borderBottom: '1px solid var(--border)' }}>
        {sheets.map((sheet, i) => {
          const isActive = i === activeTab;
          const isEmpty = sheet.rows.length < 2;
          const isChartTab = isEmpty && (chartsOn(sheet.name).length > 0 || hasChart);
          const displayName = sheetRenames?.[i] ?? sheet.name;

          if (renamingTab === i) {
            return (
              <input
                key={i}
                autoFocus
                value={renameValue}
                onChange={(e) => setRenameValue(e.target.value)}
                onBlur={() => commitRename(i)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') { e.preventDefault(); commitRename(i); }
                  if (e.key === 'Escape') setRenamingTab(null);
                }}
                style={{
                  padding: '5px 13px', fontSize: 12, fontFamily: mono, fontWeight: 600,
                  border: '1px solid var(--accent)', borderRadius: '6px 6px 0 0',
                  background: 'var(--canvas)', color: 'var(--text)', outline: 'none',
                  width: `${Math.max(8, renameValue.length + 2)}ch`, position: 'relative', bottom: -1,
                }}
              />
            );
          }

          return (
            <button
              key={i}
              onClick={() => setActiveTab(i)}
              onDoubleClick={() => { setRenamingTab(i); setRenameValue(displayName); }}
              title="Double-click to rename sheet"
              style={{
                padding: '6px 14px', fontSize: 12, fontFamily: mono, fontWeight: isActive ? 600 : 400,
                border: '1px solid var(--border)', borderBottom: isActive ? '1px solid var(--canvas)' : '1px solid var(--border)',
                borderRadius: '6px 6px 0 0', background: isActive ? 'var(--canvas)' : 'var(--surface-2)',
                color: isActive ? 'var(--text)' : 'var(--text-faint)',
                cursor: 'pointer', position: 'relative', bottom: -1,
              }}
            >
              {isChartTab ? '⬡ ' : ''}{displayName}
            </button>
          );
        })}
      </div>

      {/* A chart-only sheet shows the chart in place of a grid of nothing — but a sheet that is
          merely empty still gets its grid. */}
      {!(isChartOnly && (resolvedCharts.length > 0 || showFallback)) && (
        <Grid
          key={activeTab}
          rows={displayRows}
          sheetIdx={activeTab}
          editedKeys={editedKeys}
          onCellEdit={onCellEdit}
          totalRows={activeSheet.totalRows}
          theme={theme}
          onWidth={setGridWidth}
        />
      )}

      {resolvedCharts.map((chart, i) => (
        <div key={i} style={{ marginTop: isChartOnly && i === 0 ? 0 : 28 }}>
          <ChartPreview data={chart} type={chart.type} title={chart.title} theme={theme} />
        </div>
      ))}

      {showFallback && (
        <div style={{ marginTop: isChartOnly ? 0 : 28 }}>
          <ChartPreview data={fallbackData} theme={theme} note={SYNTHESISED} />
        </div>
      )}
    </div>
  );
}
