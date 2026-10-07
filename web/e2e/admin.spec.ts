import { auditor, people } from './support/people';
import { expect, ribbon, signIn, test } from './support/test';

test('the auditor can check that the whole book balances', async ({ page }) => {
  await signIn(page, auditor);
  await ribbon(page, 'Health').click();

  await expect(page.getByText('The ledger is consistent.')).toBeVisible();
  await page.getByRole('button', { name: 'Check again' }).click();
  await expect(page.getByText(/Last checked at/)).toBeVisible();
});

test('nobody else is offered the check, or can run it', async ({ page }) => {
  await signIn(page, people.asha);
  await expect(ribbon(page, 'Health')).toHaveCount(0);

  await page.goto('/admin');
  await expect(page.getByText('The check could not be run. Only auditors may run it.')).toBeVisible();
});
