import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('./chatApi', () => ({
  getChatMessages: vi.fn(),
  sendChatMessage: vi.fn(),
  streamChatMessage: vi.fn(),
  revertChatSession: vi.fn(),
  StreamUnavailable: class extends Error {},
}));

import ChatPanel from './ChatPanel.jsx';
import { getChatMessages } from './chatApi';

/**
 * The product's claim is that every answer is auditable: the reply carries the chain of steps that
 * produced it, in plain language. That chain is what is checked here — it is folded away by default,
 * it says how many steps there were, and opening it shows the sentences the backend wrote.
 */

const reply = {
  id: 1,
  role: 'assistant',
  content: 'Widget A sold most, 1 240 units.',
  createdAt: '2026-08-26T09:00:00Z',
  steps: [
    { order: 1, text: 'Read A1:D500 on "Sales"', success: true },
    { order: 2, text: 'Found the largest value in column C', success: true },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ChatPanel', () => {
  it('shows the reply with its step chain folded away', async () => {
    getChatMessages.mockResolvedValue([reply]);

    render(<ChatPanel open sessionId="s1" hasFile onOpenChange={() => {}} />);

    expect(await screen.findByText('Widget A sold most, 1 240 units.')).toBeInTheDocument();
    expect(screen.getByText(/How I got this · 2 steps/)).toBeInTheDocument();
    expect(screen.queryByText('Read A1:D500 on "Sales"')).not.toBeInTheDocument();
  });

  it('shows the steps in plain language when the chain is opened', async () => {
    getChatMessages.mockResolvedValue([reply]);

    render(<ChatPanel open sessionId="s1" hasFile onOpenChange={() => {}} />);
    fireEvent.click(await screen.findByText(/How I got this/));

    await waitFor(() => {
      expect(screen.getByText('Read A1:D500 on "Sales"')).toBeInTheDocument();
    });
    expect(screen.getByText('Found the largest value in column C')).toBeInTheDocument();
  });

  it('marks a turn that had a failing step, rather than reading as a clean answer', async () => {
    getChatMessages.mockResolvedValue([
      { ...reply, steps: [{ order: 1, text: 'Sort A2:D20 by column C', success: false }] },
    ]);

    render(<ChatPanel open sessionId="s1" hasFile onOpenChange={() => {}} />);

    expect(await screen.findByText(/1 failed/)).toBeInTheDocument();
  });

  it('asks for a file before it asks for a question', () => {
    render(<ChatPanel open sessionId={null} hasFile={false} onOpenChange={() => {}} />);

    // No session yet, so there is nothing to load and nothing to ask about.
    expect(getChatMessages).not.toHaveBeenCalled();
    expect(screen.getAllByText(/upload|sheet|file/i).length).toBeGreaterThan(0);
  });
});
