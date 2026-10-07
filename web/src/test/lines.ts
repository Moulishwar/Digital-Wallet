import type { StatementLine } from '../api/types';

let next = 0;

/** A statement line with sensible defaults; a test spells out only what it is about. */
export function line(overrides: Partial<StatementLine> = {}): StatementLine {
  next += 1;
  return {
    lineId: `line-${next}`,
    journalEntryId: `entry-${next}`,
    occurredAt: '2026-10-07T06:30:00Z',
    type: 'TRANSFER',
    description: null,
    amountMinor: -5000,
    balanceAfterMinor: 95000,
    counterpartyHandle: 'ravi',
    counterpartyName: 'Ravi Kumar',
    memo: null,
    ...overrides,
  };
}
