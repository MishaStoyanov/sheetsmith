import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SuggestionsPanel from './SuggestionsPanel.jsx';
import SuggestionCard from './SuggestionCard.jsx';

/**
 * The plan cards are the only review step between a model's idea and someone's spreadsheet, so what
 * is checked here is what a person has to be able to read off one: the sentence the backend wrote,
 * whether the step is going to run, and — for a type the panel has no icon for — that they are never
 * shown a raw action name in place of a category.
 */

const step = (over = {}) => ({
  type: 'FORMAT_CELLS',
  description: 'Give A1:D1 a blue background (#1E3A8A), white text, bold',
  properties: { range: 'A1:D1' },
  status: 'pending',
  ...over,
});

describe('SuggestionsPanel', () => {
  it('shows the backend’s sentence rather than the action name', () => {
    render(<SuggestionsPanel steps={[step()]} onToggleStep={() => {}} onEditStep={() => {}} onApply={() => {}} />);

    expect(screen.getByText(/Give A1:D1 a blue background/)).toBeInTheDocument();
    expect(screen.queryByText('FORMAT_CELLS')).not.toBeInTheDocument();
  });

  it('counts only the steps that will actually run', () => {
    render(
      <SuggestionsPanel
        steps={[step(), step({ status: 'dismissed' }), step()]}
        onToggleStep={() => {}}
        onEditStep={() => {}}
        onApply={() => {}}
      />,
    );

    expect(screen.getByText('2 of 3 selected')).toBeInTheDocument();
  });

  it('never puts a raw action name on a card, even for a type it has no icon for', () => {
    render(
      <SuggestionsPanel
        steps={[step({ type: 'COLOR_SCALE', description: 'Shade C2:C500 by value', properties: { range: 'C2:C500' } })]}
        onToggleStep={() => {}}
        onEditStep={() => {}}
        onApply={() => {}}
      />,
    );

    expect(screen.getByText('Shade C2:C500 by value')).toBeInTheDocument();
    expect(screen.queryByText(/COLOR_SCALE/)).not.toBeInTheDocument();
    expect(screen.getByText('Color scale')).toBeInTheDocument();
    expect(screen.getByText('C2:C500')).toBeInTheDocument();
  });
});

describe('SuggestionCard', () => {
  const item = {
    type: 'FORMAT_CELLS',
    mark: '◈',
    cat: 'Format',
    ref: 'A1:D1',
    title: 'Give A1:D1 a blue background',
    status: 'pending',
  };

  it('toggles the step when the card is clicked', () => {
    const onToggle = vi.fn();
    render(<SuggestionCard item={item} properties={{ range: 'A1:D1' }} onToggle={onToggle} onEdit={() => {}} />);

    fireEvent.click(screen.getByText('Give A1:D1 a blue background'));

    expect(onToggle).toHaveBeenCalledOnce();
  });

  it('strikes a dismissed step through and drops its editable fields', () => {
    render(
      <SuggestionCard
        item={{ ...item, status: 'dismissed' }}
        properties={{ range: 'A1:D1' }}
        onToggle={() => {}}
        onEdit={() => {}}
      />,
    );

    expect(screen.getByText('Give A1:D1 a blue background')).toHaveStyle({ textDecoration: 'line-through' });
    expect(screen.queryByDisplayValue('A1:D1')).not.toBeInTheDocument();
  });

  it('reports an edit to the range without toggling the card', () => {
    const onEdit = vi.fn();
    const onToggle = vi.fn();
    render(<SuggestionCard item={item} properties={{ range: 'A1:D1' }} onToggle={onToggle} onEdit={onEdit} />);

    fireEvent.change(screen.getByDisplayValue('A1:D1'), { target: { value: 'A1:E1' } });

    expect(onEdit).toHaveBeenCalledWith('range', 'A1:E1');
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('commits the edit when the field loses focus', () => {
    const onCommitEdit = vi.fn();
    render(
      <SuggestionCard
        item={item}
        properties={{ range: 'A1:D1' }}
        onToggle={() => {}}
        onEdit={() => {}}
        onCommitEdit={onCommitEdit}
      />,
    );

    fireEvent.blur(screen.getByDisplayValue('A1:D1'));

    expect(onCommitEdit).toHaveBeenCalledOnce();
  });
});
