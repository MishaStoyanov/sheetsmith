import { authFetch } from './authApi.js';
// Same-origin by design — see the note in api.js.
const BASE = '';

async function readErrorMessage(res, fallback) {
  try {
    const body = await res.json();
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

// → { sessionId, filename, revision, sheets: [names], charts: [definitions] }
export async function createChatSession(file) {
  const fd = new FormData();
  fd.append('file', file);
  const res = await authFetch(`${BASE}/api/chat/sessions`, { method: 'POST', body: fd });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not start chat: ${res.status}`));
  return res.json();
}

// → { sessionId, filename, revision, sheets: [names], charts: [definitions] }
export async function getChatSession(sessionId) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not load chat session: ${res.status}`));
  return res.json();
}

// → { message: {id, role, content, steps, revisionAfter, createdAt}, mutated, revision }
export async function sendChatMessage(sessionId, text) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Message failed: ${res.status}`));
  return res.json();
}

/**
 * Raised when the streaming transport never got going — the caller falls back to sendChatMessage
 * and says nothing, because a working chat over a plain POST is not something to alarm anyone about.
 */
export class StreamUnavailable extends Error {}

/**
 * The same turn as sendChatMessage, but the tool calls arrive as they happen.
 *
 * EventSource is not an option here (it cannot POST), so this reads the body itself and splits the
 * SSE frames by hand. `onStep` gets each step; the resolved value is the final ChatTurnDto.
 */
export async function streamChatMessage(sessionId, text, onStep) {
  let res;
  try {
    res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ text }),
    });
  } catch {
    throw new StreamUnavailable();
  }
  if (!res.ok || !res.body?.getReader) throw new StreamUnavailable();

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let result = null;
  let failure = null;
  let frames = 0;

  const consume = (frame) => {
    let event = null;
    let data = '';
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
    }
    if (!event || !data) return;
    frames++;
    let payload;
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'step') onStep?.(payload);
    else if (event === 'done') result = payload;
    else if (event === 'error') failure = payload?.message;
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let split;
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      consume(buffer.slice(0, split));
      buffer = buffer.slice(split + 2);
    }
  }
  if (buffer.trim()) consume(buffer);

  if (failure) throw new Error(failure);
  if (result) return result;
  // Nothing came through at all — a proxy that buffers or strips the stream. Worth one plain POST.
  if (frames === 0) throw new StreamUnavailable();
  throw new Error('The assistant’s reply was cut off.');
}

// → [message, ...]
export async function getChatMessages(sessionId) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/messages`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not load messages: ${res.status}`));
  return res.json();
}

// → ArrayBuffer of the current .xlsx
export async function getChatFile(sessionId) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/file`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not fetch the sheet: ${res.status}`));
  return res.arrayBuffer();
}

// Undo — append-only, so the returned revision is a NEW one holding the older content.
// Covers improve runs as well as chat edits: both live in the same chain.
// → { revision }
// Commits the grid's manual edits as one revision. Without this they live only in the browser
// and are dropped the moment anything refreshes the sheet.
export async function saveChatEdits(sessionId, cellEdits, sheetRenames) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/edits`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      cells: cellEdits.map(({ sheetIdx, ri, ci, value }) => ({
        sheetIndex: sheetIdx, row: ri, column: ci, value: String(value ?? ''),
      })),
      sheetRenames,
    }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Saving edits failed: ${res.status}`));
  return res.json();
}

export async function revertChatSession(sessionId, revision) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}/revert`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ revision }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Undo failed: ${res.status}`));
  return res.json();
}

export async function deleteChatSession(sessionId) {
  const res = await authFetch(`${BASE}/api/chat/sessions/${sessionId}`, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) {
    throw new Error(await readErrorMessage(res, `Could not close chat: ${res.status}`));
  }
}
