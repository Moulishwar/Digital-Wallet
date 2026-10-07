import type { Page } from '@playwright/test';
import { people, WIDE_NOTE } from './people';
import { expect } from './test';

export interface Screen {
  name: string;
  /** Brings the screen up, starting from anywhere in the signed-in app. */
  open: (page: Page) => Promise<void>;
  /** Activity and the Ledger are turned a page at a time, and on a desktop must never scroll. */
  paged?: boolean;
}

const wideName = people.wide.fullName;

/** Every screen Asha can reach, including the ones showing the widest name and note there are. */
export const screens: Screen[] = [
  {
    name: 'home',
    open: async (page) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { name: 'Recent entries' })).toBeVisible();
    },
  },
  {
    name: 'activity',
    paged: true,
    open: async (page) => {
      await page.goto('/activity');
      await expect(page.getByText(WIDE_NOTE, { exact: true })).toBeVisible();
    },
  },
  {
    name: 'activity, entry with the widest name and note',
    paged: true,
    open: async (page) => {
      await page.goto('/activity');
      await page.getByRole('button', { name: `To ${wideName}` }).click();
      await expect(page.getByText('Journal entry').first()).toBeVisible();
    },
  },
  {
    name: 'ledger',
    paged: true,
    open: async (page) => {
      await page.goto('/ledger');
      await expect(page.getByText('Matches the balance your wallet reports.')).toBeVisible();
    },
  },
  {
    name: 'send, finding the widest name',
    open: async (page) => {
      await page.goto(`/send?to=${people.wide.handle}`);
      await expect(page.getByRole('button', { name: `Yes, pay ${wideName}` })).toBeVisible();
    },
  },
  {
    name: 'send, reviewing the widest name and note',
    open: async (page) => {
      await page.goto(`/send?to=${people.wide.handle}`);
      await page.getByRole('button', { name: `Yes, pay ${wideName}` }).click();
      await page.getByLabel('Amount').fill('1');
      await page.getByLabel('Note (optional)').fill(WIDE_NOTE);
      await page.getByRole('button', { name: 'Review' }).click();
      await expect(page.getByRole('button', { name: 'Pay ₹1.00' })).toBeVisible();
    },
  },
  {
    name: 'add test money',
    open: async (page) => {
      await page.goto('/add');
      await expect(page.getByRole('heading', { name: 'How much test money?' })).toBeVisible();
    },
  },
  {
    name: 'profile',
    open: async (page) => {
      await page.goto('/profile');
      await expect(page.getByText('Receive money')).toBeVisible();
    },
  },
];

/**
 * The vouchers stamped when money moves. Each pays or adds money, so they are opened by someone
 * registered for the test, never by Asha, whose history the paging tests read.
 */
export const voucherScreens: Screen[] = [
  {
    name: 'paid voucher, to the widest name with the widest note',
    open: async (page) => {
      await page.goto(`/send?to=${people.wide.handle}`);
      await page.getByRole('button', { name: `Yes, pay ${wideName}` }).click();
      await page.getByLabel('Amount').fill('1');
      await page.getByLabel('Note (optional)').fill(WIDE_NOTE);
      await page.getByRole('button', { name: 'Review' }).click();
      await page.getByRole('button', { name: 'Pay ₹1.00' }).click();
      await expect(page.getByRole('img', { name: /^Stamped paid/ })).toBeVisible();
    },
  },
  {
    name: 'credited voucher',
    open: async (page) => {
      await page.goto('/add');
      await page.getByRole('button', { name: '₹1,000.00' }).click();
      await page.getByRole('button', { name: 'Add test money' }).click();
      await expect(page.getByRole('img', { name: /^Stamped credited/ })).toBeVisible();
    },
  },
];

/** Lets web fonts land and layout settle before anything is measured. */
export async function settled(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );
}
