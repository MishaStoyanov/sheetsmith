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

export async function getOllamaModels(baseUrl) {
  const res = await authFetch(`${BASE}/api/settings/ollama/models?baseUrl=${encodeURIComponent(baseUrl)}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to fetch Ollama models: ${res.status}`);
  }
  return (await res.json()).models;
}

/**
 * What this instance can do, decided at startup. Read once on load: an instance running with the
 * chat off must not be offered a chat panel or a "what would you improve?" button, because both
 * would fail — and, more to the point, because their absence is the guarantee such an instance
 * exists for.
 */
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

async function userError(res, fallback) {
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
  if (!res.ok) throw new Error(await userError(res, 'Could not create that account'));
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
  if (!res.ok) throw new Error(await userError(res, 'Could not set that spend limit'));
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
  if (!res.ok) throw new Error(await userError(res, 'Could not change that role'));
  return res.json();
}

/** PATCH: only what is sent changes. `currentPassword` is required to change your own. */
export async function updateUser(id, patch) {
  const res = await authFetch(`${BASE}/api/users/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(await userError(res, 'Could not save that change'));
  return res.json();
}

export async function deleteUser(id) {
  const res = await authFetch(`${BASE}/api/users/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await userError(res, 'Could not delete that account'));
}

export async function getCapabilities() {

  const res = await authFetch(`${BASE}/api/capabilities`);
  if (!res.ok) throw new Error(`Failed to fetch capabilities: ${res.status}`);
  return res.json();
}
