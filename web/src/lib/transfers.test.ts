import { describe, expect, it } from 'vitest';
import type { Transfer } from '../api/types';
import { payee, transferState } from './transfers';

const transfer = (overrides: Partial<Transfer>): Transfer => ({
  transferId: 't-1',
  status: 'COMPLETED',
  recipientUserId: 'u-2',
  recipientHandle: 'ravi',
  recipientName: 'Ravi Kumar',
  amountMinor: 5_000,
  note: null,
  failureReason: null,
  senderBalanceAfterMinor: 95_000,
  createdAt: '2026-10-07T06:30:00Z',
  completedAt: '2026-10-07T06:30:01Z',
  ...overrides,
});

describe('transferState', () => {
  it('says Paid once the ledger has posted', () => {
    expect(transferState(transfer({}))).toBe('Paid');
  });

  it('explains a refusal in the sender’s words', () => {
    expect(transferState(transfer({ status: 'FAILED', failureReason: 'INSUFFICIENT_FUNDS' }))).toBe(
      'Refused: not enough money in your wallet at the time',
    );
    expect(transferState(transfer({ status: 'FAILED', failureReason: 'SOMETHING_NEW' }))).toBe('Refused');
  });

  it('does not claim an outcome while one is being confirmed', () => {
    for (const status of ['PENDING', 'NEEDS_RECONCILIATION'] as const) {
      expect(transferState(transfer({ status }))).toBe('Settling: the outcome is being confirmed');
    }
  });
});

describe('payee', () => {
  it('prefers the name, then the handle', () => {
    expect(payee(transfer({}))).toBe('Ravi Kumar');
    expect(payee(transfer({ recipientName: null }))).toBe('@ravi');
    expect(payee(transfer({ recipientName: null, recipientHandle: null }))).toBe('someone');
  });
});
