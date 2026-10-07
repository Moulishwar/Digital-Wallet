import { request, type APIRequestContext, type FullConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { auditor, PASSWORD, people, WIDE_NOTE, type Person } from './support/people';

/**
 * Seeds the run's people through the public API, exactly as the app would: register, sign in,
 * add test money, pay one another. Nothing is written to a database directly.
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL!;
  const api = await request.newContext({ baseURL });

  for (const p of Object.values(people)) {
    await register(api, p);
  }
  // The auditor outlives a run, so it is registered only the first time.
  const auditorSignIn = await api.post('/api/auth/login', {
    data: { email: auditor.email, password: PASSWORD },
  });
  if (!auditorSignIn.ok()) {
    await register(api, auditor);
  }
  const token = Object.fromEntries(
    await Promise.all(Object.entries(people).map(async ([key, p]) => [key, await signIn(api, p)] as const)),
  ) as Record<keyof typeof people, string>;

  for (const key of ['ravi', 'meera', 'kabir'] as const) {
    await topUp(api, token[key], 500_000);
  }
  await topUp(api, token.asha, 2_500_000);
  await pay(api, token.asha, people.ravi, 45_000, 'Chai and samosas');
  await pay(api, token.meera, people.asha, 120_000, 'Movie tickets');
  await pay(api, token.asha, people.kabir, 850_000, 'Rent share, October');
  await pay(api, token.ravi, people.asha, 30_000, 'Auto fare');
  // Enough lines that Activity and the Ledger run to several pages on any screen.
  for (let i = 1; i <= 24; i++) {
    await pay(api, token.asha, people.meera, 1_000 + i, `Small change ${i}`);
  }
  // Late enough to be on the first page of Activity, where the layout tests look for it.
  await pay(api, token.asha, people.wide, 100, WIDE_NOTE);
  await pay(api, token.kabir, people.asha, 250_000, null);

  // Refused with 422, but recorded all the same: a refusal is part of the sender's history.
  const refusal = await api.post('/api/transfers', {
    headers: authorised(token.broke),
    data: { recipientHandle: people.asha.handle, amountMinor: 10_000, note: 'Cannot afford this' },
  });
  if (refusal.status() !== 422) {
    throw new Error(`Expected the unfunded payment to be refused, but got ${refusal.status()}`);
  }
  const sent = await ok(
    await api.get('/api/transfers?size=1', { headers: { Authorization: `Bearer ${token.broke}` } }),
    'Listing the refused payment',
  );
  const refused = ((await sent.json()) as { transfers: { transferId: string; status: string }[] })
    .transfers[0];
  if (refused?.status !== 'FAILED') {
    throw new Error(`Expected the unfunded payment to be recorded as refused, but it is ${refused?.status}`);
  }
  // Global setup's environment reaches every test worker.
  process.env.E2E_REFUSED_TRANSFER = refused.transferId;

  await api.dispose();
}

async function ok(response: Awaited<ReturnType<APIRequestContext['get']>>, what: string) {
  if (!response.ok()) {
    throw new Error(`${what} failed: ${response.status()} ${await response.text()}`);
  }
  return response;
}

async function register(api: APIRequestContext, p: Person) {
  await ok(
    await api.post('/api/auth/register', {
      data: { handle: p.handle, email: p.email, password: PASSWORD, fullName: p.fullName },
    }),
    `Registering @${p.handle}`,
  );
}

async function signIn(api: APIRequestContext, p: Person): Promise<string> {
  const response = await ok(
    await api.post('/api/auth/login', { data: { email: p.email, password: PASSWORD } }),
    `Signing in @${p.handle}`,
  );
  return ((await response.json()) as { accessToken: string }).accessToken;
}

const authorised = (token: string) => ({ Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() });

async function topUp(api: APIRequestContext, token: string, amountMinor: number) {
  await ok(
    await api.post('/api/wallets/me/topups', { headers: authorised(token), data: { amountMinor } }),
    'Adding test money',
  );
}

async function pay(
  api: APIRequestContext,
  token: string,
  to: Person,
  amountMinor: number,
  note: string | null,
) {
  await ok(
    await api.post('/api/transfers', {
      headers: authorised(token),
      data: { recipientHandle: to.handle, amountMinor, note },
    }),
    `Paying @${to.handle}`,
  );
}
