// Creates the demo accounts in demo.json, with a short history between the two people, through the
// API exactly as a visitor would:
//
//   docker compose run --rm demo-seed                 (from the repository root)
//   node web/demo/seed.mjs                            (API_URL defaults to http://localhost:8080)
//
// Safe to run again: if the accounts already exist, it changes nothing.

import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

const api = process.env.API_URL ?? 'http://localhost:8080';
const demo = JSON.parse(readFileSync(process.argv[2] ?? new URL('./demo.json', import.meta.url), 'utf8'));
const account = (role, index = 0) => demo.accounts.filter((a) => a.role === role)[index];

async function call(method, path, { token, body } = {}) {
  const response = await fetch(api + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

async function waitForApi() {
  for (let attempt = 1; attempt <= 60; attempt++) {
    try {
      const { body } = await call('GET', '/actuator/health');
      if (body?.status === 'UP') return;
    } catch {
      // Not listening yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`The API at ${api} did not come up`);
}

async function signIn(a) {
  const { status, body } = await call('POST', '/api/auth/login', {
    body: { email: a.email, password: a.password },
  });
  return status === 200 ? body.accessToken : null;
}

async function must(what, request) {
  const { status, body } = await request;
  if (status >= 300) throw new Error(`${what} failed: ${status} ${JSON.stringify(body)}`);
  return body;
}

await waitForApi();

if (await signIn(demo.accounts[0])) {
  console.log('The demo accounts already exist; nothing to do.');
  process.exit(0);
}

const token = {};
for (const a of demo.accounts) {
  const { handle, fullName, email, password } = a;
  await must(
    `Registering @${handle}`,
    call('POST', '/api/auth/register', { body: { handle, fullName, email, password } }),
  );
  token[a.handle] = await signIn(a);
}

const [first, second] = [account('person', 0), account('person', 1)];
const topUp = (a, amountMinor) =>
  must(
    `Adding money for @${a.handle}`,
    call('POST', '/api/wallets/me/topups', { token: token[a.handle], body: { amountMinor } }),
  );
const pay = (from, to, amountMinor, note) =>
  must(
    `@${from.handle} paying @${to.handle}`,
    call('POST', '/api/transfers', {
      token: token[from.handle],
      body: { recipientHandle: to.handle, amountMinor, note },
    }),
  );

// A few days of ordinary life between two friends: enough for every screen to have something on it.
await topUp(first, 2_500_000);
await topUp(second, 1_000_000);
await pay(first, second, 45_000, 'Chai and samosas');
await pay(second, first, 120_000, 'Movie tickets');
await pay(first, second, 850_000, 'Rent share');
await pay(second, first, 30_000, 'Auto fare');
await pay(first, second, 64_050, 'Groceries');

// The auditor's button is pointless unless auth-service made the account an auditor.
const auditor = account('auditor');
if (auditor) {
  const me = await must(
    'Reading the auditor',
    call('GET', '/api/users/me', { token: token[auditor.handle] }),
  );
  if (!me.roles.includes('ROLE_ADMIN')) {
    throw new Error(
      `${auditor.email} is not an auditor: add it to ADMIN_EMAILS in .env and restart auth-service`,
    );
  }
}

console.log(`Seeded ${demo.accounts.length} demo accounts.`);
