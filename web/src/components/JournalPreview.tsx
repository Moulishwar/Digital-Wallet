import { formatAmount } from '../lib/money';
import entry from './EntryCard.module.css';
import styles from './JournalPreview.module.css';

export interface PreviewLine {
  account: string;
  /** Signed: negative leaves the account (debit), positive arrives (credit). */
  amountMinor: number;
}

/**
 * The journal entry a payment will write, shown before it is confirmed: the double entry at the
 * moment it matters, rather than only afterwards in a history screen.
 */
export function JournalPreview({ lines, posted }: { lines: PreviewLine[]; posted: boolean }) {
  const total = lines.reduce((sum, line) => sum + Math.max(line.amountMinor, 0), 0);

  return (
    <section className={styles.preview} aria-labelledby="preview-title">
      <h2 id="preview-title" className={styles.title}>
        {posted ? 'What was written in the ledger' : 'What will be written in the ledger'}
      </h2>
      <table className={entry.table}>
        <caption className="visually-hidden">Both lines of this entry</caption>
        <thead>
          <tr>
            <th scope="col">Account</th>
            <th scope="col" className={entry.num}>
              <abbr title="Debit: money leaving the account">Dr</abbr>
            </th>
            <th scope="col" className={entry.num}>
              <abbr title="Credit: money arriving in the account">Cr</abbr>
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.account}>
              <th scope="row">{line.account}</th>
              <td className={`${entry.num} ${entry.dr} figure`}>
                {line.amountMinor < 0 ? formatAmount(line.amountMinor) : ''}
              </td>
              <td className={`${entry.num} figure`}>
                {line.amountMinor > 0 ? formatAmount(line.amountMinor) : ''}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Totals</th>
            <td className={`${entry.num} figure`}>
              <span className={entry.total}>{formatAmount(total)}</span>
            </td>
            <td className={`${entry.num} figure`}>
              <span className={entry.total}>{formatAmount(total)}</span>
            </td>
          </tr>
        </tfoot>
      </table>
      <p className={styles.note}>
        Both lines are written in one transaction. If either cannot be written, neither is.
      </p>
    </section>
  );
}
