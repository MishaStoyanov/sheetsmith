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
/** The people who could own a run. Only reachable on an instance with accounts. */
export async function searchUsers(keyword) {
  const res = await authFetch(`${BASE}/api/users/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, size: 200, sort: 'name' }),
  });
  if (!res.ok) throw new Error(`Failed to load users: ${res.status}`);
  return (await res.json()).content;
}

export async function getCapabilities() {
  const res = await authFetch(`${BASE}/api/capabilities`);
  if (!res.ok) throw new Error(`Failed to fetch capabilities: ${res.status}`);
  return res.json();
}
