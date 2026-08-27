import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PromptRecall from './PromptRecall.jsx';

describe('PromptRecall', () => {
  it('shows nothing at all on an instance with no repeated phrasing', () => {
    const { container } = render(<PromptRecall prompts={[]} onPick={() => {}} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('offers each phrasing with how often it was used', () => {
    render(
      <PromptRecall
        prompts={[{ text: 'sort by revenue', uses: 4 }, { text: 'add quarterly totals', uses: 2 }]}
        onPick={() => {}}
      />,
    );

    expect(screen.getByText('sort by revenue')).toBeInTheDocument();
    expect(screen.getByText('×4')).toBeInTheDocument();
    expect(screen.getByText('×2')).toBeInTheDocument();
  });

  it('hands back the phrasing unshortened, not the text as it was displayed', async () => {
    // The chip is ellipsised to fit a row; picking one has to fill the field with the whole
    // sentence, since deciding which half matters belongs to whoever wrote it.
    const long = 'reconcile the invoice tab against the ledger and flag anything over thirty days';
    const onPick = vi.fn();
    render(<PromptRecall prompts={[{ text: long, uses: 3 }]} onPick={onPick} />);

    await userEvent.click(screen.getByRole('button'));

    expect(onPick).toHaveBeenCalledWith(long);
  });

  it('does not fill a field that cannot take one yet', async () => {
    const onPick = vi.fn();
    render(<PromptRecall prompts={[{ text: 'sort by revenue', uses: 2 }]} onPick={onPick} disabled />);

    await userEvent.click(screen.getByRole('button'));

    expect(onPick).not.toHaveBeenCalled();
  });
});
