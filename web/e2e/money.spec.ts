import { people } from './support/people';
import { expect, newPerson, ribbon, signIn, signOut, test } from './support/test';

test('adds test money and stamps the voucher credited', async ({ page, request }) => {
  const person = await newPerson(request, 'add', 'Farah Ali');
  await signIn(page, person);
  await page.getByRole('link', { name: 'Add test money' }).click();
  await page.getByRole('button', { name: '₹1,000.00' }).click();
  await page.getByRole('button', { name: 'Add test money' }).click();

  await expect(page.getByText('Your balance is now')).toBeVisible();
  await expect(page.getByRole('img', { name: /^Stamped credited/ })).toBeVisible();
  await ribbon(page, 'Home').click();
  await expect(page.getByLabel('1,000 rupees')).toBeVisible();
});

test('pays someone: who, how much, review, paid; and they see it', async ({ page, request }, testInfo) => {
  const payer = await newPerson(request, 'pay', 'Tara Bose', 500_000);
  const note = `Lunch, ${testInfo.project.name} ${testInfo.retry}`;
  await signIn(page, payer);

  await page.getByRole('link', { name: 'Send money' }).click();
  // Typed the way people write handles, with the @.
  await page.getByLabel('Their handle').fill(`@${people.ravi.handle}`);
  await page.getByRole('button', { name: 'Find' }).click();
  await page.getByRole('button', { name: 'Yes, pay Ravi' }).click();
  await page.getByLabel('Amount').fill('1,250.50');
  await page.getByLabel('Note (optional)').fill(note);
  await page.getByRole('button', { name: 'Review' }).click();

  await expect(page.getByText('Rupees One Thousand Two Hundred Fifty and Paise Fifty Only')).toBeVisible();
  await page.getByRole('button', { name: 'Pay ₹1,250.50' }).click();
  await expect(page.getByText(/went to\s+Ravi Kumar/)).toBeVisible();
  await expect(page.getByRole('img', { name: /^Stamped paid/ })).toBeVisible();

  await ribbon(page, 'Home').click();
  await expect(page.getByLabel('3,749 rupees 50 paise')).toBeVisible();
  await ribbon(page, 'Activity').click();
  await expect(page.getByRole('button', { name: 'To Ravi Kumar' }).first()).toBeVisible();
  await expect(page.getByText(note)).toBeVisible();

  await signOut(page);
  await signIn(page, people.ravi, '/activity');
  await expect(page.getByRole('row').filter({ hasText: note })).toContainText('From Tara Bose');
});

test('will not let a payment exceed the balance', async ({ page, request }) => {
  const person = await newPerson(request, 'cap', 'Lata Das', 10_000);
  await signIn(page, person, `/send?to=${people.ravi.handle}`);
  await page.getByRole('button', { name: 'Yes, pay Ravi' }).click();
  await page.getByLabel('Amount').fill('500');
  await page.getByRole('button', { name: 'Review' }).click();

  await expect(page.getByRole('alert')).toHaveText('That is more than your balance of ₹100.00.');
});

test('keeps a note to one ruled line: 30 characters', async ({ page }) => {
  await signIn(page, people.asha, `/send?to=${people.ravi.handle}`);
  await page.getByRole('button', { name: 'Yes, pay Ravi' }).click();
  const note = page.getByLabel('Note (optional)');
  await note.pressSequentially('a'.repeat(40));

  await expect(note).toHaveValue('a'.repeat(30));
  await expect(page.getByText('0 of 30 characters left.')).toBeVisible();
});

test('explains who could not be found, and that you cannot pay yourself', async ({ page }) => {
  await signIn(page, people.asha);
  await page.getByRole('link', { name: 'Send money' }).click();
  const handle = page.getByLabel('Their handle');

  await handle.fill('nobody_has_this_handle');
  await page.getByRole('button', { name: 'Find' }).click();
  await expect(page.getByRole('alert')).toHaveText(/No one has the handle @nobody_has_this_handle/);

  await handle.fill(people.asha.handle);
  await page.getByRole('button', { name: 'Find' }).click();
  await expect(page.getByRole('alert')).toHaveText('That is your own handle. You cannot pay yourself.');
});

test('shows a refused payment as void, never as paid', async ({ page }) => {
  await signIn(page, people.broke, `/transfers/${process.env.E2E_REFUSED_TRANSFER}`);

  await expect(page.getByText('Refused: not enough money in your wallet at the time').first()).toBeVisible();
  await expect(page.getByText('REFUSED', { exact: true })).toBeVisible();
  await expect(page.getByRole('img', { name: /^Stamped/ })).toHaveCount(0);
});
