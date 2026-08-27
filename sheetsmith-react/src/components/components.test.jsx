import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import Badge from './Badge.jsx';
import Button from './Button.jsx';
import DataTable from './DataTable.jsx';
import Field from './Field.jsx';
import Pagination from './Pagination.jsx';

const columns = [
  { key: 'name', header: 'Name', sortable: true, render: row => row.name },
  { key: 'size', header: 'Size', align: 'right', render: row => row.size },
];
const rows = [{ id: 1, name: 'first.xlsx', size: 12 }, { id: 2, name: 'second.xlsx', size: 40 }];

describe('Button', () => {
  it('does not fire while disabled', async () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>Save</Button>);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('fires when it is not', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('Field', () => {
  it('focuses its input when the label is clicked', async () => {
    // The version this replaced was a styled div, so the label was decoration. A real label is the
    // difference between a click target of twelve pixels and one of the whole row.
    render(<Field label="Username" />);

    await userEvent.click(screen.getByText('Username'));

    expect(screen.getByLabelText('Username')).toHaveFocus();
  });

  it('shows an error in place of the hint', () => {
    render(<Field label="Password" hint="At least four characters" error="Too short" />);

    expect(screen.getByText('Too short')).toBeInTheDocument();
    expect(screen.queryByText('At least four characters')).not.toBeInTheDocument();
  });
});

describe('Badge', () => {
  it('renders its label whatever the tone', () => {
    render(<Badge tone="bad">failed</Badge>);
    expect(screen.getByText('failed')).toBeInTheDocument();
  });
});

describe('DataTable', () => {
  it('shows the rows it is given', () => {
    render(<DataTable columns={columns} rows={rows} />);

    expect(screen.getByText('first.xlsx')).toBeInTheDocument();
    expect(screen.getByText('second.xlsx')).toBeInTheDocument();
  });

  it('tells "nothing yet" and "nothing matched" apart', () => {
    // One message would have to be wrong about one of them, and the two call for different actions:
    // make something happen, or widen the filter.
    const empty = { title: 'No runs yet', hint: 'Improve a spreadsheet.' };
    const emptyFiltered = { title: 'Nothing matches', hint: 'Try a wider range.' };

    const { rerender } = render(
      <DataTable columns={columns} rows={[]} empty={empty} emptyFiltered={emptyFiltered} />);
    expect(screen.getByText('No runs yet')).toBeInTheDocument();

    rerender(
      <DataTable columns={columns} rows={[]} filtered empty={empty} emptyFiltered={emptyFiltered} />);
    expect(screen.getByText('Nothing matches')).toBeInTheDocument();
  });

  it('says it is loading rather than showing an empty frame', () => {
    render(<DataTable columns={columns} rows={[]} loading empty={{ title: 'No runs yet' }} />);

    // An empty state while the first page is still in flight reads as "there is nothing", which is
    // a different and wrong answer.
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('No runs yet')).not.toBeInTheDocument();
  });

  it('keeps the rows visible while the next page loads', () => {
    render(<DataTable columns={columns} rows={rows} loading />);
    expect(screen.getByText('first.xlsx')).toBeInTheDocument();
  });

  it('asks for a sort, and flips direction on a second click', async () => {
    const onSortChange = vi.fn();
    const { rerender } = render(
      <DataTable columns={columns} rows={rows} onSortChange={onSortChange} />);

    await userEvent.click(screen.getByText(/Name/));
    expect(onSortChange).toHaveBeenLastCalledWith({ key: 'name', direction: 'asc' });

    rerender(
      <DataTable columns={columns} rows={rows} sort={{ key: 'name', direction: 'asc' }} onSortChange={onSortChange} />);
    await userEvent.click(screen.getByText(/Name/));
    expect(onSortChange).toHaveBeenLastCalledWith({ key: 'name', direction: 'desc' });
  });

  it('does not offer sorting on a column that has none', async () => {
    const onSortChange = vi.fn();
    render(<DataTable columns={columns} rows={rows} onSortChange={onSortChange} />);

    await userEvent.click(screen.getByText(/Size/));

    expect(onSortChange).not.toHaveBeenCalled();
  });
});

describe('Pagination', () => {
  it('renders nothing when there is only one page', () => {
    // Controls that can only ever be disabled are noise.
    const { container } = render(<Pagination page={0} totalPages={1} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('cannot step past either end', async () => {
    const onChange = vi.fn();
    const { rerender } = render(<Pagination page={0} totalPages={3} onChange={onChange} />);

    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(onChange).not.toHaveBeenCalled();

    rerender(<Pagination page={2} totalPages={3} onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('moves a page at a time', async () => {
    const onChange = vi.fn();
    render(<Pagination page={1} totalPages={3} onChange={onChange} />);

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(onChange).toHaveBeenCalledWith(2);
  });
});
