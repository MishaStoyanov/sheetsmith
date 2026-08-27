import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import UnpricedModelsNote from './UnpricedModelsNote.jsx';

const many = n => Array.from({ length: n }, (_, i) => `OLLAMA / model-${i + 1}`);

describe('UnpricedModelsNote', () => {
  it('says nothing when everything used could be priced', () => {
    const { container } = render(<UnpricedModelsNote models={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('names a single model inside the sentence rather than after a colon', () => {
    render(<UnpricedModelsNote models={['OLLAMA / gemma4:12b']} />);

    expect(screen.getByText(/OLLAMA \/ gemma4:12b/)).toBeInTheDocument();
    expect(screen.getByText(/has no price, so its calls/)).toBeInTheDocument();
    expect(screen.queryByText(/One model/)).toBeNull();
  });

  it('counts and lists when there are a few', () => {
    render(<UnpricedModelsNote models={['OLLAMA / a', 'OLLAMA / b', 'OLLAMA / c']} />);

    expect(screen.getByText(/3 models have no price/)).toBeInTheDocument();
    expect(screen.getByText('OLLAMA / c')).toBeInTheDocument();
  });

  it('stops naming and starts counting once the list would run away', () => {
    // Anybody running locally swaps models the way other people change a setting, so this list
    // grows on its own. Printed in full it stops being a sentence.
    render(<UnpricedModelsNote models={many(9)} />);

    expect(screen.getByText(/9 models have no price/)).toBeInTheDocument();
    expect(screen.getByText('OLLAMA / model-4')).toBeInTheDocument();
    expect(screen.queryByText('OLLAMA / model-5')).toBeNull();
    expect(screen.getByText(/and 5 more/)).toBeInTheDocument();
  });

  it('keeps the whole list reachable on hover even when it is cut', () => {
    // Read off the attribute rather than through getByTitle, which normalises the newlines away
    // and would pass on a title holding only the first name.
    const { container } = render(<UnpricedModelsNote models={many(9)} />);

    expect(container.querySelector('[title]').getAttribute('title')).toBe(many(9).join('\n'));
  });

  it('does not say "and 0 more" when the list ends exactly at the cut', () => {
    render(<UnpricedModelsNote models={many(4)} />);

    expect(screen.getByText('OLLAMA / model-4')).toBeInTheDocument();
    expect(screen.queryByText(/more/)).toBeNull();
  });
});
