import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import TimeBarChart from './TimeBarChart.jsx';
import { seriesColor } from './seriesColors.js';

const format = value => `${value}`;

function fills() {
  return [...document.querySelectorAll('rect[fill]')]
    .map(rect => rect.getAttribute('fill'))
    .filter(fill => fill !== 'transparent' && fill.startsWith('#'));
}

describe('TimeBarChart', () => {
  it('says so in words rather than drawing empty axes', () => {
    render(<TimeBarChart title="Tokens" buckets={[]} format={format} theme="dark" empty="No calls in this range" />);

    expect(screen.getByText('No calls in this range')).toBeInTheDocument();
    expect(document.querySelector('svg')).toBeNull();
  });

  it('draws one colour and no legend for a single measure', () => {
    render(
      <TimeBarChart
        title="Tokens"
        buckets={[{ label: '2026-08-01', value: 10 }, { label: '2026-08-02', value: 20 }]}
        format={format}
        theme="dark"
      />,
    );

    expect(new Set(fills()).size).toBe(1);
  });

  it('stacks the parts in the order it was given', () => {
    const keys = ['dana', 'admin'];
    render(
      <TimeBarChart
        title="Tokens"
        buckets={[{ label: '2026-08-01', value: 30, parts: { dana: 20, admin: 10 } }]}
        keys={keys}
        format={format}
        theme="dark"
      />,
    );

    expect(fills()).toEqual([seriesColor('dana', keys, 'dark'), seriesColor('admin', keys, 'dark')]);
  });

  it('keeps a colour with its person when another has nothing to show', () => {
    // The failure this guards against is a filter or a measure change repainting the survivors:
    // the colour follows the name, never its position among the parts that happen to be drawn.
    const keys = ['No owner', 'dana', 'admin'];
    const danaBlue = seriesColor('dana', keys, 'dark');

    render(
      <TimeBarChart
        title="Spend"
        buckets={[{ label: '2026-08-01', value: 30, parts: { 'No owner': 0, dana: 20, admin: 10 } }]}
        keys={keys}
        format={format}
        theme="dark"
      />,
    );

    expect(fills()).toContain(danaBlue);
    expect(fills()).not.toContain(seriesColor('No owner', keys, 'dark'));
  });

  it('names in the legend only the parts it actually drew', () => {
    render(
      <TimeBarChart
        title="Spend"
        buckets={[{ label: '2026-08-01', value: 30, parts: { 'No owner': 0, dana: 20, admin: 10 } }]}
        keys={['No owner', 'dana', 'admin']}
        format={format}
        theme="dark"
      />,
    );

    expect(screen.getByText('dana')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.queryByText('No owner')).toBeNull();
  });
});
