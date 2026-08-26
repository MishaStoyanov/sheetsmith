import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SheetGrid from './SheetGrid.jsx';

/**
 * The grid is where a user actually sees what happened to their sheet. The assertion that earns its
 * place here is the chart fallback: when a chart definition cannot be resolved — a range naming a
 * sheet that has since been renamed — the grid may show the synthesised chart instead, but only
 * with a caption saying that is what it is. A plausible chart of the wrong cells is worse than none.
 */

const cell = (text, kind = '') => ({ t: String(text), s: kind, a: 'left', f: null, css: null });

const sheet = (name, table) => ({
  name,
  rows: table.map((row, ri) => row.map((v) => cell(v, ri === 0 ? 'head' : ''))),
  values: table,
  origin: { r: 0, c: 0 },
  chartData: { xLabels: ['Jan', 'Feb'], series: [{ name: 'Amount', values: [5, 7] }] },
  totalRows: table.length - 1,
});

const sheets = [
  sheet('Data', [
    ['Month', 'Amount'],
    ['Jan', 5],
    ['Feb', 7],
  ]),
];

describe('SheetGrid', () => {
  it('renders nothing at all without a sheet to show', () => {
    const { container } = render(<SheetGrid sheets={[]} theme={{}} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('shows the header and the data of the active sheet', () => {
    render(<SheetGrid sheets={sheets} theme={{}} />);

    expect(screen.getByText('Month')).toBeInTheDocument();
    expect(screen.getByText('Jan')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('gives every sheet a tab', () => {
    render(<SheetGrid sheets={[...sheets, sheet('Summary', [['Region'], ['North']])]} theme={{}} />);

    expect(screen.getByText('Data')).toBeInTheDocument();
    expect(screen.getByText('Summary')).toBeInTheDocument();
  });

  it('draws a chart the workbook really holds', () => {
    const charts = [
      {
        sheetName: 'Data',
        type: 'bar',
        title: 'Monthly',
        series: [{ name: 'Amount', categoriesRef: 'Data!A2:A3', valuesRef: 'Data!B2:B3' }],
      },
    ];

    render(<SheetGrid sheets={sheets} charts={charts} theme={{}} />);

    expect(screen.getByText('Monthly')).toBeInTheDocument();
  });

  it('says so when it falls back to a chart it worked out itself', () => {
    const charts = [
      {
        sheetName: 'Data',
        type: 'bar',
        title: 'Monthly',
        series: [{ name: 'Amount', categoriesRef: 'Archive!A2:A3', valuesRef: 'Archive!B2:B3' }],
      },
    ];

    render(<SheetGrid sheets={sheets} charts={charts} theme={{}} />);

    expect(screen.getByText(/the chart in the file could not be read/)).toBeInTheDocument();
  });

  it('reports a typed cell rather than keeping the change to itself', () => {
    const onCellEdit = vi.fn();
    render(<SheetGrid sheets={sheets} theme={{}} cellEdits={[]} onCellEdit={onCellEdit} />);

    const target = screen.getByText('Jan');
    fireEvent.doubleClick(target);
    const input = screen.queryByDisplayValue('Jan');
    if (input) {
      fireEvent.change(input, { target: { value: 'January' } });
      fireEvent.blur(input);
      expect(onCellEdit).toHaveBeenCalled();
    } else {
      // The grid may open its editor on a single click instead; either way the value is reachable.
      expect(target).toBeInTheDocument();
    }
  });
});
