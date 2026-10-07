import type { Page, TestInfo } from '@playwright/test';
import { people } from './support/people';
import { screens, settled, voucherScreens, type Screen } from './support/screens';
import { expect, newPerson, signIn, test } from './support/test';

/**
 * Measured, not eyeballed: the hostile data seeded for every run (a 60-letter name with no
 * spaces, a 30-letter note with no spaces) must never push anything off its page.
 */

/** Elements that stick out past either side of the book page they are on. */
function escapees(page: Page) {
  return page.evaluate(() => {
    const found: string[] = [];
    for (const section of document.querySelectorAll('main > section')) {
      const edge = section.getBoundingClientRect();
      for (const el of section.querySelectorAll('*')) {
        const box = el.getBoundingClientRect();
        if (box.width === 0 || box.height === 0) continue;
        const by = Math.max(box.right - edge.right, edge.left - box.left);
        if (by > 1) {
          found.push(
            `<${el.tagName.toLowerCase()}> "${(el.textContent ?? '').trim().slice(0, 24)}" by ${Math.round(by)}px`,
          );
        }
      }
    }
    return found;
  });
}

/**
 * How far the page is wider than the screen. Measured against the device's width, not the
 * window's: Android's Chrome quietly widens its layout viewport to fit content that is too wide,
 * which would hide the very overflow being looked for.
 */
const sideways = async (page: Page) =>
  (await page.evaluate(() => document.documentElement.scrollWidth)) - page.viewportSize()!.width;
const downwards = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);

async function measure(page: Page, list: Screen[], testInfo: TestInfo) {
  for (const screen of list) {
    await test.step(screen.name, async () => {
      await screen.open(page);
      await settled(page);
      expect(await sideways(page), 'window scrolls sideways by').toBeLessThanOrEqual(0);
      // Folded onto a phone, the page is the screen and the window check above is the one that matters.
      if (testInfo.project.name === 'desktop') {
        expect(await escapees(page)).toEqual([]);
      }
      await testInfo.attach(screen.name, { body: await page.screenshot(), contentType: 'image/png' });
    });
  }
}

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`${scheme} theme`, () => {
    test.use({ colorScheme: scheme });

    test('no screen scrolls sideways, and nothing escapes its page', async ({ page }, testInfo) => {
      await signIn(page, people.asha);
      await measure(page, screens, testInfo);
    });

    test('nor do the stamped vouchers, signed with the longest handle there can be', async ({
      page,
      request,
    }, testInfo) => {
      const payer = await newPerson(request, 'lay', 'Zoya Qureshi', 100_000, { longestHandle: true });
      await signIn(page, payer);
      await measure(page, voucherScreens, testInfo);
    });
  });
}

for (const height of [768, 900, 1080]) {
  test(`a desktop page of Activity or the Ledger fits a ${height}px window without scrolling`, async ({
    page,
  }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', 'Phones scroll the folded page by design');
    await page.setViewportSize({ width: 1440, height });
    await signIn(page, people.asha);
    for (const screen of screens.filter((s) => s.paged)) {
      await test.step(screen.name, async () => {
        await screen.open(page);
        await settled(page);
        expect(await downwards(page), 'page scrolls down by').toBeLessThanOrEqual(0);
      });
    }
  });
}
