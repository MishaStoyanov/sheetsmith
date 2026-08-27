// Same-origin by design — see the note in api.js.
const BASE = '';

// The access token lives here and nowhere else. Not localStorage: a value a script can read is a
// value an injected script can read, and the whole reason the long-lived half is an httpOnly
// cookie would be undone by keeping the short-lived half in reach.
let accessToken = null;
let expiresAt = 0;
let currentUser = null;

// Every refresh in flight shares one promise. Without this, five requests that expire together
// start five refreshes; rotation spends the cookie on the first, the other four present a token
// that is already used, and the server — correctly — treats that as a stolen token and ends every
// session the person has. The user is thrown out at the exact moment the app was busiest.
let refreshInFlight = null;

// Whether this instance has accounts at all, answered once by /api/capabilities. Kept here rather
// than passed into every call so that no API module has to know the difference.
let authEnabled = false;

export function configureAuth(enabled) {
  authEnabled = enabled;
}

const listeners = new Set();

function announce() {
  listeners.forEach((listener) => listener(currentUser));
}

/** Called whenever the signed-in user changes, including on sign-out (with null). */
export function onAuthChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getCurrentUser() {
  return currentUser;
}

function remember(auth) {
  accessToken = auth.accessToken;
  // A minute early, so a request is never sent with a token that expires mid-flight.
  expiresAt = Date.now() + auth.expiresInSeconds * 1000 - 60_000;
  currentUser = auth.user;
  announce();
  return auth;
}

function forget() {
  accessToken = null;
  expiresAt = 0;
  currentUser = null;
  announce();
}

async function readErrorMessage(res, fallback) {
  try {
    const body = await res.json();
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function login(name, password, rememberMe) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ name, password, rememberMe }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, 'Sign-in failed'));
  return remember(await res.json());
}

export async function logout() {
  try {
    await fetch(`${BASE}/api/auth/logout`, { method: 'POST', credentials: 'include' });
  } catch {
    // Signing out cannot fail from the user's side: the visible part is local, and it has just
    // happened. Rethrowing would make every caller handle an error for an action that succeeded
    // as far as anyone can see. The cost of a failed call is that the refresh token stays valid on
    // the server until it expires on its own, which is the best available answer with no network.
  } finally {
    // Cleared even when the call failed: the alternative is an app that looks signed in while
    // holding a token the server has already withdrawn.
    forget();
  }
}

/**
 * Trades the cookie for a new access token. Shared by everything, so concurrent callers wait on one
 * exchange rather than racing each other into the replay defence.
 */
export function refresh() {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = fetch(`${BASE}/api/auth/refresh`, { method: 'POST', credentials: 'include' })
    .then(async (res) => {
      if (!res.ok) {
        forget();
        throw new Error(await readErrorMessage(res, 'Session expired'));
      }
      return remember(await res.json());
    })
    .finally(() => {
      refreshInFlight = null;
    });

  return refreshInFlight;
}

/** Restores a session on page load from the cookie alone — the access token never outlives a reload. */
export async function restoreSession() {
  try {
    return await refresh();
  } catch {
    return null;
  }
}

async function validToken() {
  if (accessToken && Date.now() < expiresAt) return accessToken;
  await refresh();
  return accessToken;
}

/**
 * fetch, with the token attached and one retry on 401.
 *
 * The retry is what makes expiry invisible. It is deliberately once: a second 401 after a fresh
 * token means the answer is not about expiry at all, and retrying again would spin.
 */
export async function authFetch(input, init = {}) {
  // Untouched on an instance without accounts: attaching a header there would be harmless, but
  // trying to mint a token for it would send a pointless refresh on every single call.
  if (!authEnabled) return fetch(input, init);

  const send = async (token) => {
    const headers = new Headers(init.headers || {});
    if (token) headers.set('Authorization', `Bearer ${token}`);
    return fetch(input, { ...init, headers, credentials: 'include' });
  };

  let res = await send(await validToken());
  if (res.status === 401) {
    try {
      await refresh();
    } catch {
      forget();
      return res;
    }
    res = await send(accessToken);
  }
  return res;
}
