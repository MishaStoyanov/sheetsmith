import { authFetch } from './authApi.js';

// Same-origin by design — see the note in api.js.
const BASE = '';

async function priceError(res, fallback) {
  try {
    const body = await res.json();
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function searchPrices(keyword, page = 0, size = 50) {
  const res = await authFetch(`${BASE}/api/prices/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, page, size }),
  });
  if (!res.ok) throw new Error(await priceError(res, `Could not load prices: ${res.status}`));
  return res.json();
}

/**
 * PUT: the price for this model from now on.
 *
 * There is no separate create — provider plus model is the address, so setting a price for a model
 * nobody has priced yet is the same operation as changing one.
 */
export async function putPrice(price) {
  const res = await authFetch(`${BASE}/api/prices`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(price),
  });
  if (!res.ok) throw new Error(await priceError(res, 'Could not save that price'));
  return res.json();
}

/** PATCH: only the figures sent are changed. */
export async function patchPrice(id, patch) {
  const res = await authFetch(`${BASE}/api/prices/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(await priceError(res, 'Could not update that price'));
  return res.json();
}

/**
 * Removes a price.
 *
 * `confirm` is the server's guard, not the screen's: without it a price that recorded calls depend
 * on is refused outright, and the refusal carries the number. The screen asks first so the number
 * is seen before the decision, but a script that skips the screen still meets the same wall.
 */
export async function deletePrice(id, confirm = false) {
  const res = await authFetch(`${BASE}/api/prices/${id}?confirm=${confirm}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await priceError(res, 'Could not delete that price'));
}
