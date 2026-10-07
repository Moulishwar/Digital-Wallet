import { describe, expect, it } from 'vitest';
import { line } from '../test/lines';
import { pageTotals } from './ledger';

describe('pageTotals', () => {
  it('has nothing to add up on an empty page', () => {
    expect(pageTotals([])).toBeNull();
  });

  it('works b/f + Cr − Dr from the lines alone', () => {
    const totals = pageTotals([
      line({ amountMinor: 20_000, balanceAfterMinor: 120_000 }),
      line({ amountMinor: -5_000, balanceAfterMinor: 115_000 }),
      line({ amountMinor: 2_050, balanceAfterMinor: 117_050 }),
    ]);
    expect(totals).toEqual({
      lineCount: 3,
      broughtForward: 100_000,
      credits: 22_050,
      debits: 5_000,
      worked: 117_050,
      carriedForward: 117_050,
    });
  });

  it('exposes a page whose written balance does not follow from its lines', () => {
    const totals = pageTotals([
      line({ amountMinor: -5_000, balanceAfterMinor: 95_000 }),
      line({ amountMinor: -5_000, balanceAfterMinor: 91_000 }),
    ]);
    expect(totals?.worked).toBe(90_000);
    expect(totals?.carriedForward).toBe(91_000);
  });
});
