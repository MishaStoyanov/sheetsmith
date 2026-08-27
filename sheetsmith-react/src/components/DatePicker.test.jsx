import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DatePicker from './DatePicker.jsx';
import Checkbox from './Checkbox.jsx';
import DefaultPasswordNotice from './DefaultPasswordNotice.jsx';

describe('DatePicker', () => {
  it('shows a placeholder until a date is chosen', () => {
    render(<DatePicker value="" onChange={vi.fn()} placeholder="From" />);
    expect(screen.getByRole('button', { name: /From/ })).toBeInTheDocument();
  });

  it('reports the day as a local ISO date, not a shifted one', async () => {
    // toISOString would move the day by one for anybody east or west of UTC, which is exactly the
    // sort of bug that only shows up for other people.
    const onChange = vi.fn();
    render(<DatePicker value="2026-08-15" onChange={onChange} />);

    await userEvent.click(screen.getByRole('button', { name: /15 Aug 2026/ }));
    await userEvent.click(screen.getByRole('button', { name: '20' }));

    expect(onChange).toHaveBeenCalledWith('2026-08-20');
  });

  it('opens on the chosen month rather than on today', async () => {
    render(<DatePicker value="2020-02-10" onChange={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /10 Feb 2020/ }));

    expect(screen.getByText('February 2020')).toBeInTheDocument();
  });

  it('will not offer a day outside the range it was given', async () => {
    const onChange = vi.fn();
    render(<DatePicker value="2026-08-15" max="2026-08-16" onChange={onChange} />);

    await userEvent.click(screen.getByRole('button', { name: /15 Aug 2026/ }));
    await userEvent.click(screen.getByRole('button', { name: '25' }));

    // The pair of ends bound each other, so a backwards range cannot be entered at all rather than
    // being explained after the fact.
    expect(onChange).not.toHaveBeenCalled();
  });

  it('clears back to nothing', async () => {
    const onChange = vi.fn();
    render(<DatePicker value="2026-08-15" onChange={onChange} />);

    await userEvent.click(screen.getByRole('button', { name: /15 Aug 2026/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(onChange).toHaveBeenCalledWith('');
  });

  it('steps between months', async () => {
    render(<DatePicker value="2026-08-15" onChange={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /15 Aug 2026/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Previous month' }));

    expect(screen.getByText('July 2026')).toBeInTheDocument();
  });
});

describe('DefaultPasswordNotice', () => {
  const nagging = { id: 1, name: 'admin', mustChangePassword: true };

  beforeEach(() => localStorage.clear());

  it('says nothing once the password has been changed', () => {
    const { container } = render(<DefaultPasswordNotice user={{ id: 1, mustChangePassword: false }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('closes for now without being remembered', async () => {
    const { unmount } = render(<DefaultPasswordNotice user={nagging} />);

    await userEvent.click(screen.getByRole('button', { name: 'Later' }));
    expect(screen.queryByText(/default password/)).not.toBeInTheDocument();

    unmount();
    render(<DefaultPasswordNotice user={nagging} />);
    expect(screen.getByText(/default password/)).toBeInTheDocument();
  });

  it('stays silenced across visits when asked to', async () => {
    const { unmount } = render(<DefaultPasswordNotice user={nagging} />);

    await userEvent.click(screen.getByRole('button', { name: /Don't remind me/ }));
    unmount();

    const { container } = render(<DefaultPasswordNotice user={nagging} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is silenced per person, not per browser', async () => {
    // Two people share a machine often enough; the one who has not been warned still needs to be.
    const { unmount } = render(<DefaultPasswordNotice user={nagging} />);
    await userEvent.click(screen.getByRole('button', { name: /Don't remind me/ }));
    unmount();

    render(<DefaultPasswordNotice user={{ id: 2, name: 'dana', mustChangePassword: true }} />);
    expect(screen.getByText(/default password/)).toBeInTheDocument();
  });
});

describe('Checkbox', () => {
  it('still behaves like a checkbox, only painted differently', async () => {
    // The real input is kept and merely hidden, so the label, the keyboard and the accessibility
    // tree keep working. Painting our own would be a downgrade if it cost any of that.
    const onChange = vi.fn();
    render(<Checkbox checked={false} onChange={onChange} label="Include runs with no owner" />);

    await userEvent.click(screen.getByLabelText('Include runs with no owner'));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('can be reached and toggled from the keyboard', async () => {
    const onChange = vi.fn();
    render(<Checkbox checked={false} onChange={onChange} label="Remember me" />);

    await userEvent.tab();
    expect(screen.getByLabelText('Remember me')).toHaveFocus();
    await userEvent.keyboard(' ');

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('does not fire while disabled', async () => {
    const onChange = vi.fn();
    render(<Checkbox checked={false} disabled onChange={onChange} label="Locked" />);

    await userEvent.click(screen.getByLabelText('Locked'));

    expect(onChange).not.toHaveBeenCalled();
  });
});
