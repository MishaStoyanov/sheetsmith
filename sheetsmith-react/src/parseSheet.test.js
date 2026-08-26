import { describe, it, expect } from 'vitest';
import {
  parseA1Range,
  resolveRange,
  resolveChartDefinition,
  measureColumnWidths,
  MIN_COL,
  MAX_COL,
} from './parseSheet';

/**
 * The A1 layer is the only place in the frontend that has to agree exactly with what the backend
 * writes into a chart definition. Everything here is pure, so it is worth testing properly: a
 * mistake shows up as a chart of the wrong cells, which looks plausible and is the failure the
 * all-or-nothing rule below exists to prevent.
 */
describe('parseA1Range', () => {
  it('reads a plain range', () => {
    expect(parseA1Range('B2:D5')).toEqual({ sheet: null, r1: 1, c1: 1, r2: 4, c2: 3 });
  });

  it('treats a single cell as a range of one', () => {
    expect(parseA1Range('C3')).toEqual({ sheet: null, r1: 2, c1: 2, r2: 2, c2: 2 });
  });

  it('ignores absolute markers, which is how the backend writes anchored ranges', () => {
    expect(parseA1Range('$B$2:$D$5')).toEqual(parseA1Range('B2:D5'));
  });

  it('normalises a range given back to front', () => {
    expect(parseA1Range('D5:B2')).toEqual(parseA1Range('B2:D5'));
  });

  it('takes the sheet name off an unquoted reference', () => {
    expect(parseA1Range('Sales!A1:A3')).toMatchObject({ sheet: 'Sales', r1: 0, r2: 2 });
  });

  it('keeps a quoted sheet name whole, exclamation marks and all', () => {
    expect(parseA1Range("'Q3! final'!A1:B2")).toMatchObject({ sheet: 'Q3! final' });
  });

  it('unescapes a doubled quote inside a sheet name', () => {
    expect(parseA1Range("'Bob''s data'!A1")).toMatchObject({ sheet: "Bob's data" });
  });

  it('handles two- and three-letter columns', () => {
    expect(parseA1Range('AA1')).toMatchObject({ c1: 26 });
    expect(parseA1Range('ABC1')).toMatchObject({ c1: 730 });
  });

  it.each([
    ['not a reference', 'hello'],
    ['a whole column, which carries no rows', 'A:A'],
    ['three ends', 'A1:B2:C3'],
    ['an empty sheet name', "''!A1"],
    ['row zero', 'A0'],
    ['nothing at all', ''],
    ['a value that is not a string', 42],
  ])('refuses %s', (_why, ref) => {
    expect(parseA1Range(ref)).toBeNull();
  });
});

describe('resolveRange', () => {
  const sheets = [
    {
      name: 'Data',
      origin: { r: 0, c: 0 },
      values: [
        ['Region', 'Amount'],
        ['North', 10],
        ['South', 20],
      ],
    },
  ];

  it('reads the cells the range covers, row by row', () => {
    expect(resolveRange(sheets, 'A1:B2', 'Data')).toEqual(['Region', 'Amount', 'North', 10]);
  });

  it('falls back to the chart’s own sheet when the reference names none', () => {
    expect(resolveRange(sheets, 'A2:A3', 'Data')).toEqual(['North', 'South']);
  });

  it('prefers the sheet named in the reference over the fallback', () => {
    expect(resolveRange(sheets, 'Data!B2:B3', 'Somewhere else')).toEqual([10, 20]);
  });

  it('returns null for a sheet that is not there — renamed, or deleted', () => {
    expect(resolveRange(sheets, 'Archive!A1:A2', 'Data')).toBeNull();
  });

  it('returns null when every cell in the range is empty', () => {
    expect(resolveRange(sheets, 'E10:F12', 'Data')).toBeNull();
  });

  it('honours a sheet whose parsed values start part-way in', () => {
    const offset = [{ name: 'Data', origin: { r: 4, c: 2 }, values: [['x', 'y']] }];
    expect(resolveRange(offset, 'C5:D5', 'Data')).toEqual(['x', 'y']);
  });
});

describe('resolveChartDefinition', () => {
  const sheets = [
    {
      name: 'Data',
      origin: { r: 0, c: 0 },
      values: [
        ['Jan', 5],
        ['Feb', 7],
        ['Mar', 9],
      ],
    },
  ];

  const definition = {
    type: 'bar',
    title: 'Monthly',
    sheetName: 'Data',
    series: [{ name: 'Sales', categoriesRef: 'Data!A1:A3', valuesRef: 'Data!B1:B3' }],
  };

  it('builds the chart the definition describes', () => {
    expect(resolveChartDefinition(definition, sheets)).toEqual({
      type: 'bar',
      title: 'Monthly',
      xLabels: ['Jan', 'Feb', 'Mar'],
      series: [{ name: 'Sales', values: [5, 7, 9] }],
    });
  });

  it('numbers the points when there are no categories to label them with', () => {
    const unlabelled = { ...definition, series: [{ ...definition.series[0], categoriesRef: null }] };

    expect(resolveChartDefinition(unlabelled, sheets).xLabels).toEqual(['1', '2', '3']);
  });

  it('gives up entirely when one series cannot be resolved', () => {
    const broken = {
      ...definition,
      series: [
        definition.series[0],
        { name: 'Costs', categoriesRef: 'Data!A1:A3', valuesRef: 'Gone!B1:B3' },
      ],
    };

    expect(resolveChartDefinition(broken, sheets))
      .toBeNull();
  });

  it('trims to the shorter side rather than inventing points', () => {
    const ragged = {
      ...definition,
      series: [{ name: 'Sales', categoriesRef: 'Data!A1:A2', valuesRef: 'Data!B1:B3' }],
    };

    const chart = resolveChartDefinition(ragged, sheets);
    expect(chart.xLabels).toEqual(['Jan', 'Feb']);
    expect(chart.series[0].values).toEqual([5, 7]);
  });

  it('drops a placeholder title rather than showing it', () => {
    const untitled = { ...definition, title: '(untitled)' };

    expect(resolveChartDefinition(untitled, sheets).title).toBeNull();
  });

  it('returns null for a definition with no series', () => {
    expect(resolveChartDefinition({ ...definition, series: [] }, sheets)).toBeNull();
    expect(resolveChartDefinition(null, sheets)).toBeNull();
  });
});

describe('measureColumnWidths', () => {
  it('keeps every column inside the readable bounds', () => {
    const widths = measureColumnWidths([['a'], ['x'.repeat(500)]]);

    expect(Math.min(...widths)).toBeGreaterThanOrEqual(MIN_COL);
    expect(Math.max(...widths)).toBeLessThanOrEqual(MAX_COL);
  });
});
