import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Fresh module per test: the token and the in-flight refresh are module state on purpose, and a
// test that inherited them would be testing the previous test's session.
//
// Every test that expects a token on the wire has to switch accounts on first, because the module
// defaults to off — an instance without accounts must not send a pointless refresh before each call.
async function loadAuth({ authEnabled = true } = {}) {
  vi.resetModules();
  const auth = await import('./authApi.js');
  auth.configureAuth(authEnabled);
  return auth;
}

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}

const session = (token, seconds = 7200) => ({
  accessToken: token,
  expiresInSeconds: seconds,
  user: { id: 1, name: 'dana', mustChangePassword: false },
});

describe('authApi', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends the credentials and keeps the token out of storage', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('token-1')));

    await auth.login('dana', 'correct-horse', true);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toContain('/api/auth/login');
    expect(options.credentials).toBe('include');
    expect(JSON.parse(options.body)).toEqual({
      name: 'dana',
      password: 'correct-horse',
      rememberMe: true,
    });
    // The token is module state, not localStorage: a value a script can read is a value an
    // injected script can read.
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(auth.getCurrentUser().name).toBe('dana');
  });

  it('attaches the token to an ordinary request', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('token-1')));
    await auth.login('dana', 'pw', false);

    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));
    await auth.authFetch('/api/history');

    const [, options] = fetchMock.mock.calls[1];
    expect(options.headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('refreshes once and retries when a request comes back 401', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('old')));
    await auth.login('dana', 'pw', false);

    fetchMock
      .mockResolvedValueOnce(jsonResponse({ message: 'expired' }, 401)) // the call
      .mockResolvedValueOnce(jsonResponse(session('fresh'))) // the refresh
      .mockResolvedValueOnce(jsonResponse({ ok: true })); // the retry

    const res = await auth.authFetch('/api/history');

    expect(res.status).toBe(200);
    const retry = fetchMock.mock.calls[3];
    expect(retry[1].headers.get('Authorization')).toBe('Bearer fresh');
  });

  it('does not retry a second time when the fresh token is also refused', async () => {
    // A second 401 is not about expiry, so retrying again would only spin.
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('old')));
    await auth.login('dana', 'pw', false);

    fetchMock
      .mockResolvedValueOnce(jsonResponse({}, 401))
      .mockResolvedValueOnce(jsonResponse(session('fresh')))
      .mockResolvedValueOnce(jsonResponse({}, 401));

    const res = await auth.authFetch('/api/history');

    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('shares one refresh between concurrent callers', async () => {
    // The whole reason this file has a queue. Five parallel refreshes would spend the rotating
    // cookie once and present an already-used token four times — which the server reads as a
    // stolen token and answers by ending every session the person has.
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('old', -1)));
    await auth.login('dana', 'pw', false);

    let resolveRefresh;
    const refreshCall = new Promise((resolve) => {
      resolveRefresh = resolve;
    });
    fetchMock.mockImplementation((url) => {
      if (String(url).includes('/api/auth/refresh')) return refreshCall;
      return Promise.resolve(jsonResponse({ ok: true }));
    });

    const calls = [
      auth.authFetch('/api/history'),
      auth.authFetch('/api/settings'),
      auth.authFetch('/api/capabilities'),
    ];
    resolveRefresh(jsonResponse(session('fresh')));
    await Promise.all(calls);

    const refreshes = fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/auth/refresh'));
    expect(refreshes).toHaveLength(1);
  });

  it('forgets the session when the refresh itself is refused', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('old')));
    await auth.login('dana', 'pw', false);

    fetchMock.mockResolvedValueOnce(jsonResponse({ message: 'Session expired' }, 401));
    await expect(auth.refresh()).rejects.toThrow('Session expired');

    expect(auth.getCurrentUser()).toBeNull();
  });

  it('restores a session from the cookie alone, and answers null when there is none', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('restored')));

    expect((await auth.restoreSession()).user.name).toBe('dana');

    const fresh = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    expect(await fresh.restoreSession()).toBeNull();
  });

  it('clears local state on sign-out even if the call fails', async () => {
    const auth = await loadAuth();
    fetchMock.mockResolvedValueOnce(jsonResponse(session('token-1')));
    await auth.login('dana', 'pw', false);

    fetchMock.mockRejectedValueOnce(new Error('network down'));
    await auth.logout();

    // Otherwise the app looks signed in while holding a token the server has withdrawn.
    expect(auth.getCurrentUser()).toBeNull();
  });

  it('leaves requests alone on an instance without authentication', async () => {
    const auth = await loadAuth({ authEnabled: false });
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await auth.authFetch('/api/history', {}, { authEnabled: false });

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers).toBeUndefined();
  });
});
