import { people, PASSWORD } from './support/people';
import { expect, newPerson, ribbon, signIn, test } from './support/test';

test('opens a new account from the cover', async ({ page }, testInfo) => {
  const handle = `new_${testInfo.project.name}_${process.env.E2E_RUN}`.slice(0, 32);
  await page.goto('/');
  await page.getByRole('button', { name: 'Open an account' }).click();
  await page.getByLabel('Your name').fill('Neha Joshi');
  await page.getByLabel('Handle').fill(handle);
  await page.getByLabel('Email').fill(`${handle}@e2e.example.com`);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Open my account' }).click();

  await expect(page.getByRole('heading', { name: 'Neha Joshi' })).toBeVisible();
  await expect(page.getByText(`@${handle}`)).toBeVisible();
  await expect(page.getByLabel('0 rupees')).toBeVisible();
});

test('refuses a wrong password without saying which half was wrong', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Email').fill(people.asha.email);
  await page.getByLabel('Password').fill('not-the-password');
  await page.getByRole('button', { name: 'Open my book' }).click();

  await expect(page.getByRole('alert')).toHaveText('Invalid email or password');
});

test('stays signed in across a reload, with no token anywhere script can read', async ({ page, context }) => {
  await signIn(page, people.asha);
  await page.reload();

  await expect(page.getByRole('heading', { name: people.asha.fullName })).toBeVisible();
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(stored).not.toMatch(/eyJ/); // the start of every JWT
  expect(await page.evaluate(() => document.cookie)).toBe('');

  const refresh = (await context.cookies()).find((cookie) => cookie.name === 'dw_refresh');
  expect(refresh).toMatchObject({ httpOnly: true, sameSite: 'Strict', path: '/api/auth' });
});

test('closing the book ends the session on the server too', async ({ page, request }) => {
  const person = await newPerson(request, 'close', 'Om Prakash');
  await signIn(page, person);
  await ribbon(page, 'Profile').click();
  await page.getByRole('button', { name: 'Close my book' }).click();
  await expect(page.getByRole('button', { name: 'Open my book' })).toBeVisible();

  await page.reload();
  await expect(page.getByRole('button', { name: 'Open my book' })).toBeVisible();
});

test('closes itself after 15 minutes without activity', async ({ page, request }) => {
  const person = await newPerson(request, 'idle', 'Usha Pillai');
  await page.clock.install();
  await signIn(page, person);

  await page.clock.runFor('16:00');

  await expect(page.getByText('Your book closed itself after 15 minutes without activity.')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Open my book' })).toBeVisible();
});

test('arriving from a payment link lands on that payment after signing in', async ({ page }) => {
  await signIn(page, people.ravi, `/send?to=${people.meera.handle}`);

  await expect(page.getByRole('button', { name: 'Yes, pay Meera' })).toBeVisible();
});
