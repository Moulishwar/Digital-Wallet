import type { Problem, TokenResponse } from './types';

/**
 * The one way this app talks to the API.
 *
 * The access token lives in this module's memory and nowhere else: not localStorage, not
 * sessionStorage. The refresh token never reaches JavaScript at all: auth-service sets it as an
 * httpOnly cookie when a request carries the transport header below. A page reload therefore
 * starts with no access token, and the first call recovers one silently from the cookie.
 */

const TRANSPORT = { 'X-Token-Transport': 'cookie' };

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
let onSignedOut: () => void = () => {};

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.status = status;
    this.problem = problem;
  }
}

/** Called once by the auth provider, so an expired session anywhere can end the session UI. */
export function setSignedOutHandler(handler: () => void): void {
  onSignedOut = handler;
}

export function hasSession(): boolean {
  return accessToken !== null;
}

async function problemOf(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem;
  } catch {
    return { status: response.status, title: response.statusText };
  }
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one attempt:
 * firing two refreshes at once would present the same single-use token twice, which the server
 * rightly treats as theft and answers by revoking every session.
 */
export function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const response = await fetch('/api/auth/refresh', { method: 'POST', headers: TRANSPORT });
      if (!response.ok) {
        accessToken = null;
        return false;
      }
      accessToken = ((await response.json()) as TokenResponse).accessToken;
      return true;
    } catch {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

export async function signIn(email: string, password: string): Promise<void> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...TRANSPORT },
    body: JSON.stringify({ email, password }),
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  accessToken = ((await response.json()) as TokenResponse).accessToken;
}

export async function signOut(): Promise<void> {
  accessToken = null;
  // Revokes the session server-side and deletes the cookie. Best effort: being offline must not
  // leave someone looking at a wallet they asked to close.
  await fetch('/api/auth/logout', { method: 'POST', headers: TRANSPORT }).catch(() => undefined);
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  /** Public endpoints such as registration: send no token, and do not try to refresh one. */
  anonymous?: boolean;
}

/**
 * Calls the API and returns the parsed body. A 401 triggers one silent refresh and one retry;
 * if that fails too, the session is over.
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const send = () =>
    fetch(path, {
      method: options.method ?? 'GET',
      headers: {
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(accessToken && !options.anonymous ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...options.headers,
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });

  let response = await send();

  if (response.status === 401 && !options.anonymous) {
    if (await refreshSession()) {
      response = await send();
    }
    if (response.status === 401) {
      accessToken = null;
      onSignedOut();
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
