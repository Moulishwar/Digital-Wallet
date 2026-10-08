import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { auditor, people } from './support/people';
import { screens, settled, voucherScreens } from './support/screens';
import { expect, newPerson, signIn, signOut, test } from './support/test';

/** WCAG 2.2 at level AA, the standard the UI is built to. */
async function audit(page: Page) {
  await settled(page);
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  return violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`);
}

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`${scheme} theme`, () => {
    test.use({ colorScheme: scheme });

    test('the cover meets WCAG 2.2 AA', async ({ page }) => {
      await page.goto('/');
      await expect(page.getByRole('button', { name: 'Open my book' })).toBeVisible();
      expect(await audit(page)).toEqual([]);
      await page.getByRole('button', { name: 'Open an account' }).click();
      expect(await audit(page)).toEqual([]);
    });

    test('every screen in the book meets WCAG 2.2 AA', async ({ page }) => {
      await signIn(page, people.asha);
      for (const screen of screens) {
        await test.step(screen.name, async () => {
          await screen.open(page);
          expect(await audit(page)).toEqual([]);
        });
      }
    });

    test('the stamped vouchers meet WCAG 2.2 AA', async ({ page, request }) => {
      await signIn(page, await newPerson(request, 'axe', 'Gita Nair', 100_000));
      for (const screen of voucherScreens) {
        await test.step(screen.name, async () => {
          await screen.open(page);
          expect(await audit(page)).toEqual([]);
        });
      }
    });

    test('a refused payment and the auditor’s page meet WCAG 2.2 AA', async ({ page }) => {
      await signIn(page, people.broke, `/transfers/${process.env.E2E_REFUSED_TRANSFER}`);
      await expect(page.getByText('REFUSED', { exact: true })).toBeVisible();
      expect(await audit(page)).toEqual([]);

      await signOut(page);
      await signIn(page, auditor, '/admin');
      await expect(page.getByText('The ledger is consistent.')).toBeVisible();
      expect(await audit(page)).toEqual([]);
    });
  });
}
