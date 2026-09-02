import { authFetch } from './authApi.js';
// Same-origin by design — see the note in api.js.
const BASE = '';

export async function getSettings() {
  const res = await authFetch(`${BASE}/api/settings`);
  if (!res.ok) throw new Error(`Failed to load settings: ${res.status}`);
  return res.json();
}

export async function updateSettings(settings) {
  const res = await authFetch(`${BASE}/api/settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  if (!res.ok) throw new Error(`Failed to save settings: ${res.status}`);
  return res.json();
}

/**
 * Where this instance keeps its spreadsheets, and how much of them it keeps.
 *
 * Separate from the LLM settings although it sits in the same panel: the two are saved by different
 * people at different moments, and one PUT carrying both would let a superadmin move the archive by
 * changing a model name.
 */
export async function getStorageSettings() {
  const res = await authFetch(`${BASE}/api/settings/storage`);
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not read the storage settings'));
  return res.json();
}

/** Nulls are real values here — "no limit" and "wherever the instance was started". */
export async function updateStorageSettings(update) {
  const res = await authFetch(`${BASE}/api/settings/storage`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(update),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not save the storage settings'));
  return res.json();
}

/**
 * What a cloud vendor will answer to, asked with the key already saved for it.
 *
 * No key travels in this call: the server reads the stored one. A key in a query string is a key in
 * an access log.
 */
export async function getCloudModels(provider) {
  const res = await authFetch(`${BASE}/api/settings/cloud/models?provider=${encodeURIComponent(provider)}`);
  if (!res.ok) {
    // The server's sentence is the useful part here — "no key saved for GEMINI" tells somebody
    // what to do next, where a status code does not.
    throw new Error(await apiMessage(res, `Failed to fetch models: ${res.status}`));
  }
  return (await res.json()).models;
}

export async function getOllamaModels(baseUrl) {
  const res = await authFetch(`${BASE}/api/settings/ollama/models?baseUrl=${encodeURIComponent(baseUrl)}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to fetch Ollama models: ${res.status}`);
  }
  return (await res.json()).models;
}

/**
 * A page of accounts. Returns the page rather than its rows: the users screen needs the total to
 * page through, and unwrapping here would leave one caller reaching for a field the other threw
 * away.
 */
export async function searchUsers(keyword, page = 0, size = 200) {
  const res = await authFetch(`${BASE}/api/users/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, page, size, sort: 'name' }),
  });
  if (!res.ok) throw new Error(`Failed to load users: ${res.status}`);
  return res.json();
}

/** The server's own sentence about the refusal, or a fallback when it did not send one. */
async function apiMessage(res, fallback) {
  try {
    const body = await res.json();
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function createUser(name, password) {
  const res = await authFetch(`${BASE}/api/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, password }),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not create that account'));
  return res.json();
}

/**
 * Your own limit and what you have spent against it.
 *
 * Its own call rather than a field on the session: the people who most need it are the ones who
 * cannot reach the accounts screen at all, and it changes as they work while a session does not.
 */
export async function getMySpend() {
  const res = await authFetch(`${BASE}/api/users/me/spend`);
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not read your spend'));
  return res.json();
}

/** Asks for a bigger ceiling. No amount: how much more is the decision of whoever answers. */
export async function askForMoreBudget() {
  const res = await authFetch(`${BASE}/api/users/me/budget-request`, { method: 'POST' });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not send that request'));
  return res.json();
}

/** Marks the answer as read, which is what makes the notification happen once. */
export async function markBudgetDecisionSeen() {
  const res = await authFetch(`${BASE}/api/users/me/budget-request/seen`, { method: 'POST' });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not dismiss that'));
}

/** Requests still waiting, already narrowed to the ones this caller may answer. */
export async function getPendingBudgetRequests() {
  const res = await authFetch(`${BASE}/api/users/budget-requests`);
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not load requests'));
  return res.json();
}

/** Answers one. `newLimit` is required to approve, because approving is what raises the limit. */
export async function decideBudgetRequest(id, approve, newLimit) {
  const res = await authFetch(`${BASE}/api/users/budget-requests/${id}/decide`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approve, newLimit }),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not answer that request'));
  return res.json();
}

/**
 * Sets or clears a monthly spend limit. Null is a real value — "no limit" — which is why this is a
 * PUT of its own rather than a field on the patch, where null already means "leave this alone".
 */
export async function setUserBudget(id, monthlyBudget) {
  const res = await authFetch(`${BASE}/api/users/${id}/budget`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ monthlyBudget }),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not set that spend limit'));
  return res.json();
}

/**
 * Changes what somebody may do.
 *
 * Its own call rather than a field on the patch, matching the endpoint: a role arriving as one
 * optional field among several is a role that can be changed by accident.
 */
export async function changeUserRole(id, role) {
  const res = await authFetch(`${BASE}/api/users/${id}/role`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not change that role'));
  return res.json();
}

/** PATCH: only what is sent changes. `currentPassword` is required to change your own. */
export async function updateUser(id, patch) {
  const res = await authFetch(`${BASE}/api/users/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not save that change'));
  return res.json();
}

export async function deleteUser(id) {
  const res = await authFetch(`${BASE}/api/users/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await apiMessage(res, 'Could not delete that account'));
}

/**
 * What this instance can do, decided at startup. Read once on load: an instance running with the
 * chat off must not be offered a chat panel or a "what would you improve?" button, because both
 * would fail — and, more to the point, because their absence is the guarantee such an instance
 * exists for.
 */
export async function getCapabilities() {
  const res = await authFetch(`${BASE}/api/capabilities`);
  if (!res.ok) throw new Error(`Failed to fetch capabilities: ${res.status}`);
  return res.json();
}
