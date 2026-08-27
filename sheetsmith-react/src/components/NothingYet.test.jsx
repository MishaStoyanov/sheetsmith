import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import NothingYet from './NothingYet.jsx';

describe('NothingYet', () => {
  it('tells a fresh instance what to do rather than to look wider', () => {
    render(<NothingYet variant="never-used" />);

    expect(screen.getByText('Nothing to measure yet')).toBeInTheDocument();
    expect(screen.getByText('Improve a spreadsheet')).toHaveAttribute('href', '#/improve');
    expect(screen.getByText('Add model prices')).toHaveAttribute('href', '#/prices');
  });

  it('does not offer a range to clear on an instance that has never been used', () => {
    // Telling somebody to widen dates they never set is the wrong half of "empty".
    render(<NothingYet variant="never-used" onClear={() => {}} />);

    expect(screen.queryByText('Clear the dates')).toBeNull();
  });

  it('points a filtered-out range back at the whole record', () => {
    render(<NothingYet variant="filtered-out" onClear={() => {}} />);

    expect(screen.getByText('Nothing in this range')).toBeInTheDocument();
    expect(screen.getByText(/There are records here, just none between these dates/)).toBeInTheDocument();
  });

  it('clears the range when asked', async () => {
    const onClear = vi.fn();
    render(<NothingYet variant="filtered-out" onClear={onClear} />);

    await userEvent.click(screen.getByText('Clear the dates'));

    expect(onClear).toHaveBeenCalled();
  });

  it('leaves out the clear button when there is no range set to clear', () => {
    render(<NothingYet variant="filtered-out" />);

    expect(screen.queryByText('Clear the dates')).toBeNull();
  });
});
