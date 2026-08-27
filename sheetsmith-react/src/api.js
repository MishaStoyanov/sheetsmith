import { authFetch } from './authApi.js';
// Same-origin by design: in production Spring Boot serves this bundle, and in dev the Vite
// server proxies /api to the backend (see vite.config.js). Never hardcode a host here.
const BASE = '';

async function readErrorMessage(res, fallback) {
  try {
    const body = await res.json();
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function uploadFile(file, instruction) {
  const fd = new FormData();
  fd.append('file', file);
  fd.append('instruction', instruction);
  const res = await authFetch(`${BASE}/api/excel/improve`, { method: 'POST', body: fd });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Upload failed: ${res.status}`));
  return (await res.json()).jobId;
}

// Plans against the session's current revision — there is no file to send, the server already
// owns the sheet. A session is therefore mandatory before anything can be planned.
export async function generatePlan(sessionId, instruction) {
  const res = await authFetch(`${BASE}/api/excel/plan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, instruction }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Plan generation failed: ${res.status}`));
  return res.json(); // { planToken, steps: [{index, type, properties, description}] }
}

// Asks the assistant what it would improve — it inspects the data first, so the suggestions are
// grounded in the sheet rather than in its column names.
export async function suggestPlan(sessionId) {
  const res = await authFetch(`${BASE}/api/excel/suggest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Suggestions failed: ${res.status}`));
  return res.json();
}

// Re-narrates edited steps so a card never describes a range the step no longer targets.
export async function describeSteps(steps) {
  const res = await authFetch(`${BASE}/api/excel/describe`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ steps }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Describe failed: ${res.status}`));
  return (await res.json()).steps;
}

export async function applyPlan(planToken, steps) {
  const res = await authFetch(`${BASE}/api/excel/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ planToken, steps }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Apply failed: ${res.status}`));
  return (await res.json()).jobId;
}

/** The run list the History screen shows. Newest first is the server's default. */
export async function getHistory(page = 0, size = 20) {
  const res = await authFetch(`${BASE}/api/history?page=${page}&size=${size}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `History failed: ${res.status}`));
  return res.json();
}

/** The filtered history. Filters travel as a body — see the note on the endpoint. */
export async function searchHistory(filters) {
  const res = await authFetch(`${BASE}/api/history/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(filters),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `History failed: ${res.status}`));
  return res.json();
}

/** One run with its steps; the list view leaves them out. */
export async function getJobDetail(jobId) {
  const res = await authFetch(`${BASE}/api/history/${jobId}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not load run ${jobId}`));
  return res.json();
}

export async function deleteJob(jobId) {
  const res = await authFetch(`${BASE}/api/history/${jobId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not delete run ${jobId}`));
}

/** Everything the analytics screen shows, in one answer — see the note on the endpoint. */
export async function getAnalyticsSummary(query) {
  const res = await authFetch(`${BASE}/api/analytics/summary`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(query),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, `Analytics failed: ${res.status}`));
  return res.json();
}

/**
 * Phrasings the caller has used more than once.
 *
 * There is no parameter for whose — the endpoint answers for the caller and nobody else, which is
 * the whole design: a prompt is somebody describing their own data in their own words.
 */
export async function getFrequentPrompts(kind = 'IMPROVE', limit = 5) {
  const res = await authFetch(`${BASE}/api/prompts/frequent?kind=${kind}&limit=${limit}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Could not read past prompts: ${res.status}`));
  return res.json();
}

export async function getJobStatus(jobId) {
  const res = await authFetch(`${BASE}/api/history/${jobId}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Status check failed: ${res.status}`));
  return res.json();
}

export async function downloadResult(jobId) {
  const res = await authFetch(`${BASE}/api/history/${jobId}/download`);
  if (!res.ok) throw new Error(await readErrorMessage(res, `Download failed: ${res.status}`));
  return res.arrayBuffer();
}

export function getDownloadUrl(jobId) {
  return `${BASE}/api/history/${jobId}/download`;
}
