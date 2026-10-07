import type { StatementLine } from '../api/types';

export interface PageTotals {
  lineCount: number;
  broughtForward: number;
  credits: number;
  debits: number;
  /** b/f + Cr − Dr, worked from the lines. */
  worked: number;
  /** The balance the server wrote on the page's last line. */
  carriedForward: number;
}

/** A ledger page's arithmetic, from its lines in the order they were written. */
export function pageTotals(lines: StatementLine[]): PageTotals | null {
  if (lines.length === 0) {
    return null;
  }
  const first = lines[0];
  const last = lines[lines.length - 1];
  const broughtForward = first.balanceAfterMinor - first.amountMinor;
  const credits = lines.filter((l) => l.amountMinor > 0).reduce((sum, l) => sum + l.amountMinor, 0);
  const debits = lines.filter((l) => l.amountMinor < 0).reduce((sum, l) => sum - l.amountMinor, 0);
  return {
    lineCount: lines.length,
    broughtForward,
    credits,
    debits,
    worked: broughtForward + credits - debits,
    carriedForward: last.balanceAfterMinor,
  };
}
