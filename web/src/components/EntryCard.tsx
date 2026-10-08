import { useEntry } from '../api/queries';
import type { Entry, EntryLine } from '../api/types';
import { formatAmount, formatRupees } from '../lib/money';
import styles from './EntryCard.module.css';

const postedAt = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

function accountName(line: EntryLine): string {
  switch (line.party) {
    case 'YOU':
      return 'Your wallet';
    case 'COUNTERPARTY':
      return line.counterpartyName
        ? `${line.counterpartyName}’s wallet`
        : line.counterpartyHandle
          ? `@${line.counterpartyHandle}’s wallet`
          : 'The other wallet';
    case 'FUNDING':
      return 'Funding account (test money)';
    case 'FEES':
      return 'Fees account';
  }
}

const KIND = { TOPUP: 'Test money added', TRANSFER: 'Transfer', REVERSAL: 'Reversal' };

/**
 * One journal entry in full: every account it touched, split into debit and credit, and the
 * proof that the two sides are equal. The other person's balance is never shown; the API does
 * not send it.
 */
export function EntryCard({ journalEntryId }: { journalEntryId: string }) {
  const entry = useEntry(journalEntryId);

  if (entry.isPending) {
    return <p className={styles.loading}>Reading the entry…</p>;
  }
  if (entry.isError) {
    return <p className={styles.loading}>This entry could not be read.</p>;
  }
  return <EntryDetail entry={entry.data} />;
}

function EntryDetail({ entry }: { entry: Entry }) {
  const own = entry.lines.find((line) => line.party === 'YOU');
  const debits = entry.lines.filter((l) => l.amountMinor < 0).reduce((sum, l) => sum - l.amountMinor, 0);
  const credits = entry.lines.filter((l) => l.amountMinor > 0).reduce((sum, l) => sum + l.amountMinor, 0);

  return (
    <article className={styles.card} aria-labelledby="entry-title">
      <header>
        <p className={styles.kicker}>Journal entry</p>
        <h2 id="entry-title" className={styles.title}>
          {KIND[entry.type]}
        </h2>
        <p className={styles.when}>{postedAt.format(new Date(entry.postedAt))}</p>
        {entry.memo && <p className={styles.memo}>“{entry.memo}”</p>}
      </header>

      <table className={styles.table}>
        <caption className="visually-hidden">Both sides of this entry</caption>
        <thead>
          <tr>
            <th scope="col">Account</th>
            <th scope="col" className={styles.num}>
              <abbr title="Debit: money leaving the account">Dr</abbr>
            </th>
            <th scope="col" className={styles.num}>
              <abbr title="Credit: money arriving in the account">Cr</abbr>
            </th>
          </tr>
        </thead>
        <tbody>
          {entry.lines.map((line, index) => (
            <tr key={index} className={line.party === 'YOU' ? styles.own : undefined}>
              <th scope="row">{accountName(line)}</th>
              <td className={`${styles.num} ${styles.dr} figure`}>
                {line.amountMinor < 0 ? formatAmount(line.amountMinor) : ''}
              </td>
              <td className={`${styles.num} figure`}>
                {line.amountMinor > 0 ? formatAmount(line.amountMinor) : ''}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Totals</th>
            <td className={`${styles.num} figure`}>
              <span className={styles.total}>{formatAmount(debits)}</span>
            </td>
            <td className={`${styles.num} figure`}>
              <span className={styles.total}>{formatAmount(credits)}</span>
            </td>
          </tr>
        </tfoot>
      </table>

      <p className={entry.sumMinor === 0 ? styles.balanced : styles.unbalanced} role="status">
        {entry.sumMinor === 0
          ? 'Balanced: what left one account arrived in the other. Nothing was created or lost.'
          : `Unbalanced by ${formatRupees(entry.sumMinor)}. This should never happen.`}
      </p>

      {own?.balanceAfterMinor != null && (
        <p className={styles.after}>
          Your balance after this entry <span className="figure">{formatRupees(own.balanceAfterMinor)}</span>
        </p>
      )}

      <p className={`${styles.ref} figure`}>Entry {entry.journalEntryId.slice(0, 8)}</p>
    </article>
  );
}
