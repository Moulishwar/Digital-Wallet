import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type {
  Entry,
  Reconciliation,
  StatementLine,
  StatementPage,
  TopUpResult,
  Transfer,
  TransferPage,
  User,
  Wallet,
} from './types';

export const keys = {
  me: ['me'] as const,
  wallet: ['wallet'] as const,
  statement: ['statement'] as const,
  entry: (id: string) => ['entry', id] as const,
  transfers: ['transfers'] as const,
  transfer: (id: string) => ['transfer', id] as const,
  reconciliation: ['reconciliation'] as const,
};

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => api<User>('/api/users/me') });
}

export function useWallet() {
  return useQuery({ queryKey: keys.wallet, queryFn: () => api<Wallet>('/api/wallets/me') });
}

/** The newest lines of the statement — the home page's view of the book. */
export function useRecentLines(size: number) {
  return useQuery({
    queryKey: [...keys.statement, 'recent', size],
    queryFn: () => api<StatementPage>(`/api/wallets/me/statement?size=${size}`),
  });
}

/** One page of the statement, newest first, starting after the given cursor (null: the latest). */
export function useStatementPage(size: number, cursor: string | null) {
  return useQuery({
    queryKey: [...keys.statement, 'page', size, cursor],
    queryFn: () =>
      api<StatementPage>(
        `/api/wallets/me/statement?size=${size}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
      ),
    // The page being turned from stays on screen until the next one arrives, rather than blanking.
    placeholderData: keepPreviousData,
  });
}

export function useEntry(journalEntryId: string | null) {
  return useQuery({
    queryKey: keys.entry(journalEntryId ?? ''),
    queryFn: () => api<Entry>(`/api/wallets/me/entries/${journalEntryId}`),
    enabled: journalEntryId !== null,
    // A posted entry never changes.
    staleTime: Infinity,
  });
}

/** Payments this user has sent — including the ones that failed or are still settling. */
export function useSentTransfers(size: number) {
  return useQuery({
    queryKey: [...keys.transfers, size],
    queryFn: () => api<TransferPage>(`/api/transfers?size=${size}`),
  });
}

const UNSETTLED = new Set(['PENDING', 'NEEDS_RECONCILIATION']);

/** One transfer, polled while its outcome is still unknown. */
export function useTransfer(transferId: string) {
  return useQuery({
    queryKey: keys.transfer(transferId),
    queryFn: () => api<Transfer>(`/api/transfers/${transferId}`),
    refetchInterval: (query) => (query.state.data && UNSETTLED.has(query.state.data.status) ? 3000 : false),
  });
}

export function useReconciliation() {
  return useQuery({
    queryKey: keys.reconciliation,
    queryFn: () => api<Reconciliation>('/api/admin/reconciliation'),
    staleTime: 0,
  });
}

/** After money moves, every view of the book is out of date. */
function useInvalidateBook() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.wallet }),
      queryClient.invalidateQueries({ queryKey: keys.statement }),
      queryClient.invalidateQueries({ queryKey: keys.transfers }),
    ]);
}

export function useTopUp() {
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ amountMinor, idempotencyKey }: { amountMinor: number; idempotencyKey: string }) =>
      api<TopUpResult>('/api/wallets/me/topups', {
        method: 'POST',
        body: { amountMinor },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSuccess: invalidate,
  });
}

export interface SendInput {
  recipientHandle: string;
  amountMinor: number;
  note: string | null;
  idempotencyKey: string;
}

export function useSendMoney() {
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ idempotencyKey, ...body }: SendInput) =>
      api<Transfer>('/api/transfers', {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSettled: invalidate,
  });
}

export interface RecentPerson {
  handle: string;
  name: string | null;
}

/**
 * The people this user has recently paid or been paid by, newest first, derived from their own
 * statement. Nothing is uploaded and no contact list is read: the book already knows.
 */
export function recentPeople(lines: StatementLine[], limit: number): RecentPerson[] {
  const seen = new Map<string, RecentPerson>();
  for (const line of lines) {
    if (line.counterpartyHandle && !seen.has(line.counterpartyHandle)) {
      seen.set(line.counterpartyHandle, { handle: line.counterpartyHandle, name: line.counterpartyName });
    }
  }
  return [...seen.values()].slice(0, limit);
}
