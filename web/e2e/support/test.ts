import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { PASSWORD, type Person } from './people';

export { expect };

interface Fixtures {
  /** Fails the test if the page throws, or the browser blocks something under the CSP. */
  pageErrors: void;
}

export const test = base.extend<Fixtures>({
  pageErrors: [
    async ({ page }, use) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => {
        // WebKit reports a request cut short by the test navigating away as failing "due to access
        // control checks". The app only ever calls its own origin, so that is never a real CORS error.
        if (!/due to access control checks/.test(String(error))) errors.push(String(error));
      });
      page.on('console', (message) => {
        if (/Content.Security.Policy/i.test(message.text())) errors.push(message.text());
      });
      await use();
      expect(errors, 'errors on the page').toEqual([]);
    },
    { auto: true },
  ],
});

/** Signs in from the cover and waits for the book to open on Home. */
export async function signIn(page: Page, person: Person, path = '/') {
  await page.goto(path);
  await page.getByLabel('Email').fill(person.email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Open my book' }).click();
  await expect(page.getByRole('navigation', { name: 'Sections' })).toBeVisible();
}

/**
 * Closes the book from Profile and waits for the cover. Navigating away any sooner would cancel the
 * sign-out request, leaving the session alive to be restored on the next page load.
 */
export async function signOut(page: Page) {
  await ribbon(page, 'Profile').click();
  await page.getByRole('button', { name: 'Close my book' }).click();
  await expect(page.getByRole('button', { name: 'Open my book' })).toBeVisible();
}

/** One of the cloth ribbons that lead to each section. */
export function ribbon(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'Sections' }).getByRole('link', { name, exact: true });
}

/** Whether the book is folded onto one page, as on a phone. */
export function folded(page: Page) {
  return (page.viewportSize()?.width ?? 0) <= 960;
}

let made = 0;

/**
 * A person registered for one test alone, for tests that change a balance and so must not share
 * a wallet with tests running alongside them. Optionally given some test money to start with.
 */
export async function newPerson(
  request: APIRequestContext,
  label: string,
  fullName: string,
  startingMinor = 0,
  { longestHandle = false } = {},
): Promise<Person> {
  made += 1;
  const unique = `${label}${made}_${process.pid}_${process.env.E2E_RUN}`;
  // Handles may be up to 32 characters; the longest is the hardest to fit on a narrow screen.
  const handle = (longestHandle ? unique.padEnd(32, 'x') : unique).slice(0, 32);
  const person = { handle, fullName, email: `${handle}@e2e.example.com` };
  const registered = await request.post('/api/auth/register', {
    data: { ...person, password: PASSWORD },
  });
  expect(registered.status(), await registered.text()).toBe(201);
  if (startingMinor > 0) {
    const login = await request.post('/api/auth/login', {
      data: { email: person.email, password: PASSWORD },
    });
    const { accessToken } = await login.json();
    const topUp = await request.post('/api/wallets/me/topups', {
      headers: { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': randomUUID() },
      data: { amountMinor: startingMinor },
    });
    expect(topUp.ok(), await topUp.text()).toBe(true);
  }
  return person;
}
