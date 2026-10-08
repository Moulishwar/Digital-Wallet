import type { StatementLine } from '../api/types';

const dayMonth = new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short' });
const longDate = new Intl.DateTimeFormat('en-IN', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
});

/** "06 Oct": how a ledger dates a line. */
export function lineDate(iso: string): string {
  return dayMonth.format(new Date(iso));
}

/** "Tuesday, 7 October 2026": how a ledger dates a page. */
export function pageDate(date: Date = new Date()): string {
  return longDate.format(date);
}

/**
 * The "particulars" of a line, in a bookkeeper's words. Money out is "To" someone and money in is
 * "From" someone. Lines written before names were recorded fall back to plain wording rather
 * than showing an id.
 */
export function particulars(line: StatementLine): string {
  const out = line.amountMinor < 0;
  if (line.type === 'TOPUP') {
    return 'Test money added';
  }
  if (line.type === 'REVERSAL') {
    return 'Reversal';
  }
  const who = line.counterpartyName ?? (line.counterpartyHandle ? `@${line.counterpartyHandle}` : null);
  if (who) {
    return out ? `To ${who}` : `From ${who}`;
  }
  return out ? 'Payment sent' : 'Payment received';
}

const spanDay = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

/** "7 Oct 2026", or "28 Sept 2026 – 7 Oct 2026": how a ledger page is identified, by its dates. */
export function pageSpan(lines: StatementLine[]): string {
  if (lines.length === 0) {
    return '';
  }
  const times = lines.map((line) => new Date(line.occurredAt).getTime());
  const first = spanDay.format(Math.min(...times));
  const last = spanDay.format(Math.max(...times));
  return first === last ? first : `${first} – ${last}`;
}
