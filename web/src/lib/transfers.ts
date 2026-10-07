import type { Transfer } from '../api/types';

const REASONS: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'Refused: not enough money in your wallet at the time',
  ACCOUNT_NOT_ACTIVE: 'Refused: one of the wallets is frozen or closed',
  RECIPIENT_NOT_FOUND: 'Refused: the recipient’s wallet could not be found',
  REJECTED: 'Refused as invalid',
};

/** A transfer's state, in words someone who sent it would use. */
export function transferState(transfer: Transfer): string {
  switch (transfer.status) {
    case 'COMPLETED':
      return 'Paid';
    case 'FAILED':
      return REASONS[transfer.failureReason ?? ''] ?? 'Refused';
    case 'PENDING':
    case 'NEEDS_RECONCILIATION':
      return 'Settling: the outcome is being confirmed';
  }
}

export function payee(transfer: Transfer): string {
  return transfer.recipientName ?? (transfer.recipientHandle ? `@${transfer.recipientHandle}` : 'someone');
}
