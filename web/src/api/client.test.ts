import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type Client = typeof import('./client');

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const header = (init: RequestInit | undefined, name: string) =>
  (init?.headers as Record<string, string> | undefined)?.[name];

let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let client: Client;

beforeEach(async () => {
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetchMock);
  // The access token is module state; every test starts signed out.
  vi.resetModules();
  client = await import('./client');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const calls = (path: string) => fetchMock.mock.calls.filter(([url]) => url === path);

describe('signIn', () => {
  it('asks for the refresh token as a cookie, then sends the access token on later calls', async () => {
    fetchMock.mockImplementation(async (url) =>
      url === '/api/auth/login' ? json(200, { accessToken: 'abc' }) : json(200, { ok: true }),
    );

    await client.signIn('asha@example.com', 'Correct-Horse-9x');
    await client.api('/api/users/me');

    expect(header(calls('/api/auth/login')[0][1], 'X-Token-Transport')).toBe('cookie');
    expect(header(calls('/api/users/me')[0][1], 'Authorization')).toBe('Bearer abc');
    expect(client.hasSession()).toBe(true);
  });

  it('throws the server’s explanation when sign-in is refused', async () => {
    fetchMock.mockResolvedValue(json(401, { title: 'Unauthorized', detail: 'Invalid email or password' }));

    await expect(client.signIn('asha@example.com', 'wrong')).rejects.toMatchObject({
      status: 401,
      message: 'Invalid email or password',
    });
  });
});

describe('api', () => {
  it('recovers from an expired token with one silent refresh and a retry', async () => {
    fetchMock.mockImplementation(async (url, init) => {
      if (url === '/api/auth/refresh') return json(200, { accessToken: 'fresh' });
      return header(init, 'Authorization') === 'Bearer fresh'
        ? json(200, { balanceMinor: 1 })
        : json(401, {});
    });

    await expect(client.api('/api/wallets/me')).resolves.toEqual({ balanceMinor: 1 });
    expect(calls('/api/wallets/me')).toHaveLength(2);
  });

  it('shares one refresh between calls that expire together', async () => {
    // Two refreshes at once would present the same single-use token twice, which the server
    // treats as theft and answers by revoking every session.
    fetchMock.mockImplementation(async (url, init) => {
      if (url === '/api/auth/refresh') {
        await new Promise((resolve) => setTimeout(resolve, 10));
        return json(200, { accessToken: 'fresh' });
      }
      return header(init, 'Authorization') === 'Bearer fresh' ? json(200, { url }) : json(401, {});
    });

    await Promise.all([client.api('/api/a'), client.api('/api/b'), client.api('/api/c')]);
    expect(calls('/api/auth/refresh')).toHaveLength(1);
  });

  it('ends the session when the refresh is refused', async () => {
    const signedOut = vi.fn();
    client.setSignedOutHandler(signedOut);
    fetchMock.mockResolvedValue(json(401, { title: 'Unauthorized' }));

    await expect(client.api('/api/wallets/me')).rejects.toBeInstanceOf(client.ApiError);
    expect(signedOut).toHaveBeenCalledOnce();
    expect(client.hasSession()).toBe(false);
  });

  it('neither sends a token nor refreshes for an anonymous call', async () => {
    fetchMock.mockImplementation(async (url) =>
      url === '/api/auth/login' ? json(200, { accessToken: 'abc' }) : json(401, {}),
    );
    await client.signIn('asha@example.com', 'Correct-Horse-9x');

    await expect(
      client.api('/api/auth/register', { method: 'POST', body: {}, anonymous: true }),
    ).rejects.toThrow();
    expect(header(calls('/api/auth/register')[0][1], 'Authorization')).toBeUndefined();
    expect(calls('/api/auth/refresh')).toHaveLength(0);
  });

  it('turns Problem Details into an error that says what went wrong', async () => {
    fetchMock.mockResolvedValue(json(422, { title: 'Unprocessable', detail: 'Insufficient funds' }));

    const error = await client.api('/api/transfers', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(client.ApiError);
    expect(error).toMatchObject({ status: 422, message: 'Insufficient funds' });
  });

  it('copes with an error that has no body', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
    );

    await expect(client.api('/api/wallets/me')).rejects.toMatchObject({
      status: 502,
      message: 'Bad Gateway',
    });
  });

  it('returns nothing for 204 No Content', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(client.api('/api/something', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});

describe('signOut', () => {
  it('forgets the token even when the server cannot be reached', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'abc' }));
    await client.signIn('asha@example.com', 'Correct-Horse-9x');
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await client.signOut();
    expect(client.hasSession()).toBe(false);
  });
});
