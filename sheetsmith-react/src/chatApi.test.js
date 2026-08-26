import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { streamChatMessage, StreamUnavailable, sendChatMessage } from './chatApi';

/**
 * The SSE reader is hand-written because EventSource cannot POST, and its fallback is deliberately
 * silent — which means a break in it is a failure nobody would ever see reported. These tests feed
 * it the awkward shapes a real stream produces: frames split across chunk boundaries, CRLF line
 * endings from a proxy, an error frame, and a stream that never starts at all.
 */

/** A ReadableStream over the given chunks, so the code under test reads exactly what a fetch would. */
function streamOf(chunks) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    getReader: () => ({
      read: async () =>
        i < chunks.length ? { value: encoder.encode(chunks[i++]), done: false } : { done: true },
    }),
  };
}

function respondWith(chunks, { ok = true, status = 200 } = {}) {
  return vi.fn().mockResolvedValue({ ok, status, body: streamOf(chunks) });
}

const step = (order) => `event:step\ndata:${JSON.stringify({ order, tool: 'READ_RANGE' })}\n\n`;
const done = (content) =>
  `event:done\ndata:${JSON.stringify({ message: { content }, mutated: false, revision: 3 })}\n\n`;

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('streamChatMessage', () => {
  it('reports each step and resolves with the final turn', async () => {
    global.fetch = respondWith([step(1), step(2), done('All done')]);
    const steps = [];

    const turn = await streamChatMessage('s1', 'hello', (s) => steps.push(s));

    expect(steps.map((s) => s.order)).toEqual([1, 2]);
    expect(turn.message.content).toBe('All done');
    expect(turn.revision).toBe(3);
  });

  it('reassembles a frame split across two chunks', async () => {
    const frame = done('Split');
    global.fetch = respondWith([frame.slice(0, 12), frame.slice(12)]);

    const turn = await streamChatMessage('s1', 'hello');

    expect(turn.message.content).toBe('Split');
  });

  it('reads frames a proxy has rewritten with CRLF line endings', async () => {
    global.fetch = respondWith([step(1).replace(/\n/g, '\r\n'), done('ok').replace(/\n/g, '\r\n')]);
    const steps = [];

    const turn = await streamChatMessage('s1', 'hello', (s) => steps.push(s));

    expect(steps).toHaveLength(1);
    expect(turn.message.content).toBe('ok');
  });

  it('reads a last frame that arrived without its trailing blank line', async () => {
    global.fetch = respondWith([done('Truncated separator').replace(/\n\n$/, '')]);

    const turn = await streamChatMessage('s1', 'hello');

    expect(turn.message.content).toBe('Truncated separator');
  });

  it('raises the message from an error frame', async () => {
    global.fetch = respondWith([
      step(1),
      `event:error\ndata:${JSON.stringify({ message: 'The model gave up' })}\n\n`,
    ]);

    await expect(streamChatMessage('s1', 'hello')).rejects.toThrow('The model gave up');
  });

  it('skips a frame whose data is not JSON rather than failing the turn', async () => {
    global.fetch = respondWith(['event:step\ndata:{not json\n\n', done('Survived')]);

    const turn = await streamChatMessage('s1', 'hello');

    expect(turn.message.content).toBe('Survived');
  });

  it('asks for a fallback when the request itself fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('network'));

    await expect(streamChatMessage('s1', 'hello')).rejects.toBeInstanceOf(StreamUnavailable);
  });

  it('asks for a fallback on a non-ok response', async () => {
    global.fetch = respondWith([], { ok: false, status: 502 });

    await expect(streamChatMessage('s1', 'hello')).rejects.toBeInstanceOf(StreamUnavailable);
  });

  it('asks for a fallback when the response carries no readable body', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, body: null });

    await expect(streamChatMessage('s1', 'hello')).rejects.toBeInstanceOf(StreamUnavailable);
  });

  it('asks for a fallback when a proxy swallowed every frame', async () => {
    global.fetch = respondWith(['', '']);

    await expect(streamChatMessage('s1', 'hello')).rejects.toBeInstanceOf(StreamUnavailable);
  });

  it('reports a cut-off reply rather than a fallback once steps have arrived', async () => {
    global.fetch = respondWith([step(1), step(2)]);

    const failure = await streamChatMessage('s1', 'hello').catch((e) => e);

    expect(failure).not.toBeInstanceOf(StreamUnavailable);
    expect(failure.message).toMatch(/cut off/);
  });
});

describe('sendChatMessage', () => {
  it('returns the turn the server sent', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ message: { content: 'plain post' } }),
    });

    await expect(sendChatMessage('s1', 'hi')).resolves.toEqual({ message: { content: 'plain post' } });
  });

  it('raises the server’s own message when there is one', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ message: 'Ollama is not running' }),
    });

    await expect(sendChatMessage('s1', 'hi')).rejects.toThrow('Ollama is not running');
  });

  it('falls back to the status code when the body is not JSON', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => {
        throw new Error('not json');
      },
    });

    await expect(sendChatMessage('s1', 'hi')).rejects.toThrow('503');
  });
});
