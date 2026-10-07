/** Wire types, mirroring the backend DTOs. Amounts are always integer paise. */

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  /** Null in cookie mode, which is the only mode this app uses. */
  refreshToken: string | null;
}

export interface User {
  id: string;
  handle: string;
  email: string;
  fullName: string;
  status: string;
  roles: string[];
}

export interface Wallet {
  walletId: string;
  currency: string;
  balanceMinor: number;
  status: string;
}

export type EntryType = 'TOPUP' | 'TRANSFER' | 'REVERSAL';

export interface StatementLine {
  lineId: string;
  journalEntryId: string;
  occurredAt: string;
  type: EntryType;
  description: string | null;
  amountMinor: number;
  balanceAfterMinor: number;
  counterpartyHandle: string | null;
  counterpartyName: string | null;
  memo: string | null;
}

export interface StatementPage {
  lines: StatementLine[];
  nextCursor: string | null;
}

/** RFC 7807 Problem Details, the shape every error from the backend takes. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}

export type Party = 'YOU' | 'COUNTERPARTY' | 'FUNDING' | 'FEES';

export interface EntryLine {
  party: Party;
  counterpartyHandle: string | null;
  counterpartyName: string | null;
  amountMinor: number;
  /** Only ever set on your own line. */
  balanceAfterMinor: number | null;
}

export interface Entry {
  journalEntryId: string;
  type: EntryType;
  postedAt: string;
  description: string | null;
  memo: string | null;
  lines: EntryLine[];
  sumMinor: number;
}

export type TransferStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'NEEDS_RECONCILIATION';

export interface Transfer {
  transferId: string;
  status: TransferStatus;
  recipientUserId: string;
  recipientHandle: string | null;
  recipientName: string | null;
  amountMinor: number;
  note: string | null;
  failureReason: string | null;
  senderBalanceAfterMinor: number | null;
  createdAt: string;
  completedAt: string | null;
}

export interface TransferPage {
  transfers: Transfer[];
  nextCursor: string | null;
}

export interface UserLookup {
  userId: string;
  handle: string;
  fullName: string;
}

export interface TopUpResult {
  journalEntryId: string;
  balanceMinor: number;
  replayed: boolean;
}

export interface Reconciliation {
  consistent: boolean;
  ledgerSumMinor: number;
  accountsWithDriftedBalance: string[];
}
