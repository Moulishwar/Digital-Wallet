import type { Locator, Page } from '@playwright/test';
import { people } from './support/people';
import { expect, folded, signIn, test } from './support/test';

const earlier = (page: Page) => page.getByRole('button', { name: '← Earlier' });
const later = (page: Page) => page.getByRole('button', { name: 'Later →' });
const firstLine = (table: Locator) => table.locator('tbody tr').first().innerText();

/** The page on show, by its first line, once a page turn has finished loading. */
async function pageShowing(page: Page, table: Locator) {
  await expect(page.getByRole('navigation', { name: 'Pages' }).getByRole('button').first()).toBeEnabled();
  return firstLine(table);
}

test('Activity turns a page at a time, and remembers the way back', async ({ page }) => {
  await signIn(page, people.asha, '/activity');
  const table = page.getByRole('table', { name: /^Activity, newest first/ });

  const first = await pageShowing(page, table);
  await expect(later(page)).toHaveCount(0);

  await earlier(page).click();
  await expect(page).toHaveURL(/from=/);
  await expect.poll(() => firstLine(table)).not.toBe(first);
  const second = await pageShowing(page, table);
  await expect(later(page)).toBeVisible();

  // Opening an entry on an earlier page keeps that page, and the way back from it.
  await table.getByRole('button').first().click();
  if (folded(page)) {
    await expect(page.getByRole('dialog', { name: 'Journal entry' })).toBeVisible();
    await page.getByRole('button', { name: 'Close' }).click();
  } else {
    await expect(page.getByText('Journal entry').first()).toBeVisible();
    await page.getByRole('button', { name: 'Close this entry' }).click();
  }
  expect(await firstLine(table)).toBe(second);

  await earlier(page).click();
  await expect.poll(() => firstLine(table)).not.toBe(second);
  const third = await pageShowing(page, table);

  await page.reload();
  await expect.poll(() => firstLine(table)).toBe(third);

  await later(page).click();
  await expect.poll(() => firstLine(table)).toBe(second);
  await later(page).click();
  await expect.poll(() => firstLine(table)).toBe(first);
  await expect(later(page)).toHaveCount(0);

  await page.goBack();
  await expect.poll(() => firstLine(table)).toBe(second);
});

test('every note in Activity sits on one ruled line', async ({ page }) => {
  await signIn(page, people.asha, '/activity');
  const table = page.getByRole('table', { name: /^Activity, newest first/ });
  await expect(table.getByText('Small change 24')).toBeVisible();

  // Rows carrying a note, including the widest note there is, are all the same height: none has
  // wrapped onto an extra line.
  const rowHeights = await table
    .locator('tbody tr')
    .filter({ hasText: /Small change|W{30}/ })
    .evaluateAll((rows) => rows.map((row) => row.getBoundingClientRect().height));
  expect(rowHeights.length).toBeGreaterThan(1);
  expect(Math.max(...rowHeights) - Math.min(...rowHeights)).toBeLessThan(2);
});

test('each Ledger page proves itself and follows on from the one before', async ({ page }) => {
  await signIn(page, people.asha, '/ledger');
  const table = page.getByRole('table', { name: /^Ledger page:/ });
  const amount = async (row: Locator) => (await row.innerText()).match(/₹[\d,]+\.\d\d/)?.[0];

  await expect(page.getByText('Matches the balance written on the page’s last line.')).toBeVisible();
  await expect(page.getByText('Matches the balance your wallet reports.')).toBeVisible();
  const broughtForward = await amount(table.getByRole('row').filter({ hasText: 'b/f' }));

  await earlier(page).click();
  await expect(page.getByText('Matches the balance your wallet reports.')).toHaveCount(0);
  await expect(page.getByText('Matches the balance written on the page’s last line.')).toBeVisible();
  // What one page brings forward is what the page before it carried forward.
  await expect.poll(() => amount(table.getByRole('row').filter({ hasText: 'c/f' }))).toBe(broughtForward);
});
