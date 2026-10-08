import { expect, newPerson, ribbon, signIn, test } from './support/test';

/** As set by DEMO_RESETS_AT in compose.e2e.yml. */
const RESETS_AT = '03:30 IST';

test('the cover opens each shared demo account with one tap', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Try the demo' })).toBeVisible();
  await expect(page.getByText(`wiped back to the start at ${RESETS_AT} each night`)).toBeVisible();

  await page.getByRole('button', { name: 'Adhi', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Adhi', exact: true })).toBeVisible();
  await expect(page.getByText(`test money · resets at ${RESETS_AT}`)).toBeVisible();
});

test('the demo auditor can check the whole book', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /^Auditor/ }).click();
  await ribbon(page, 'Health').click();

  await expect(page.getByText('The ledger is consistent.')).toBeVisible();
});

test('a visitor with a new account is offered the demo people to pay', async ({ page, request }) => {
  await signIn(page, await newPerson(request, 'visit', 'Mira Sen', 100_000));
  await page.getByRole('link', { name: 'Send money' }).click();

  const suggested = page.getByRole('region', { name: 'People in this demo' });
  await expect(suggested.getByRole('button')).toHaveText(['Adhi', 'Babash']);
  await suggested.getByRole('button', { name: 'Babash' }).click();
  await expect(page.getByRole('button', { name: 'Yes, pay Babash' })).toBeVisible();
});
