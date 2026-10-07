import { useStatementPage, useWallet } from '../api/queries';
import type { StatementLine } from '../api/types';
import { Book } from '../components/Book';
import { LedgerTable } from '../components/LedgerTable';
import { PageTurner } from '../components/PageTurner';
import { pageSpan } from '../lib/entries';
import { formatAmount, formatRupees } from '../lib/money';
import { usePageSize } from '../lib/usePageSize';
import { usePageTurns } from '../lib/usePageTurns';
import page from './Page.module.css';
import styles from './Ledger.module.css';

/**
 * The accountant's view, a page at a time. The right page reads like a written ledger: top to
 * bottom in the order things happened, opening with the balance brought forward and closing with
 * the balance carried forward. The left page proves that page adds up on its own.
 */
export function Ledger() {
  const turns = usePageTurns();
  // One ruled line per entry, plus the b/f and c/f lines, sized to fit the window.
  const pageSize = usePageSize(1, 2);
  const statement = useStatementPage(pageSize, turns.cursor);
  const newestFirst = statement.data?.lines ?? [];
  const nextCursor = statement.data?.nextCursor;
  // The API pages newest first; a ledger page is written oldest first.
  const lines = [...newestFirst].reverse();
  const totals = pageTotals(lines);

  const right = (
    <div className={page.column}>
      <header className={page.pageHeader}>
        <h2 className={page.pageHeading}>Ledger</h2>
        <p className={page.span}>{pageSpan(lines)}</p>
      </header>
      <LedgerTable
        caption={`Ledger page: ${pageSpan(lines)}. Debit, credit and running balance, oldest first.`}
        variant="book"
        lines={lines}
        broughtForwardMinor={totals?.broughtForward}
        carriedForwardMinor={totals?.carriedForward}
        empty={statement.isSuccess ? 'Nothing written yet.' : undefined}
      />
      <PageTurner
        onEarlier={nextCursor ? () => turns.earlier(nextCursor) : undefined}
        onLater={turns.later}
        busy={statement.isFetching}
      />
    </div>
  );

  return (
    <Book label="Ledger" left={<Proof totals={totals} latest={turns.cursor === null} />} right={right} />
  );
}

interface Totals {
  lineCount: number;
  broughtForward: number;
  credits: number;
  debits: number;
  /** b/f + Cr − Dr, worked here. */
  worked: number;
  /** The balance the server wrote on the page's last line. */
  carriedForward: number;
}

/** The page's arithmetic, from its lines in the order they were written. */
function pageTotals(lines: StatementLine[]): Totals | null {
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

/**
 * Brought forward, plus credits, less debits, equals carried forward. Every figure is computed
 * here from the lines on the page; the last one is then checked against the balance the server
 * reports. If they ever disagreed, this page would say so.
 */
function Proof({ totals, latest }: { totals: Totals | null; latest: boolean }) {
  const wallet = useWallet();

  const header = (
    <header>
      <p className={page.kicker}>Ledger</p>
      <h1 className={page.title}>This page adds up</h1>
    </header>
  );

  if (!totals) {
    return (
      <div className={page.column}>
        {header}
        <p className={page.lead}>Once money moves, the proof that every line balances appears here.</p>
      </div>
    );
  }

  const { broughtForward, credits, debits, worked } = totals;
  const agreesWithLines = worked === totals.carriedForward;
  // Only the latest page ends at today's balance; an earlier page ends wherever the book stood then.
  const agreesWithWallet = latest && wallet.data ? worked === wallet.data.balanceMinor : null;

  return (
    <div className={page.column}>
      {header}
      <p className={page.lead}>
        Worked from the {totals.lineCount} lines on the facing page alone
        {latest ? ', then checked against the balance your wallet reports.' : '.'}
      </p>

      <table className={styles.sum}>
        <caption className="visually-hidden">The page’s arithmetic</caption>
        <tbody>
          <tr>
            <th scope="row">Brought forward</th>
            <td className="figure">{formatAmount(broughtForward)}</td>
          </tr>
          <tr>
            <th scope="row">
              Add credits <abbr title="Credit">Cr</abbr>
            </th>
            <td className="figure">+{formatAmount(credits)}</td>
          </tr>
          <tr>
            <th scope="row">
              Less debits <abbr title="Debit">Dr</abbr>
            </th>
            <td className={`figure ${page.out}`}>−{formatAmount(debits)}</td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Carried forward</th>
            <td className="figure">
              <span className={styles.total}>{formatRupees(worked)}</span>
            </td>
          </tr>
        </tfoot>
      </table>

      <ul className={styles.checks}>
        <li className={agreesWithLines ? styles.pass : styles.fail}>
          {agreesWithLines
            ? 'Matches the balance written on the page’s last line.'
            : 'Does not match the page’s last line. The ledger is inconsistent.'}
        </li>
        {agreesWithWallet !== null && (
          <li className={agreesWithWallet ? styles.pass : styles.fail}>
            {agreesWithWallet
              ? 'Matches the balance your wallet reports.'
              : 'Differs from the balance your wallet reports.'}
          </li>
        )}
      </ul>

      <p className={page.footnote}>
        Dr is money out of your wallet, Cr is money in. Each line has a twin in another account; open it from
        Activity to see both.
      </p>
    </div>
  );
}
