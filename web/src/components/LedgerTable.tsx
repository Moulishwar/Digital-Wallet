import type { ReactNode } from 'react';
import type { StatementLine } from '../api/types';
import { lineDate, particulars } from '../lib/entries';
import { formatAmount, formatRupees, formatSigned } from '../lib/money';
import { FOLDED, useMediaQuery } from '../lib/useMediaQuery';
import styles from './LedgerTable.module.css';

interface Props {
  caption: string;
  lines: StatementLine[];
  /**
   * "plain" for everyday reading — money in, money out. "book" for the accountant's view — debit,
   * credit, and the running balance after every line.
   */
  variant?: 'plain' | 'book';
  /** The balance brought forward from the previous page, written above the first line. */
  broughtForwardMinor?: number;
  /** The balance carried forward, written beneath the last line. */
  carriedForwardMinor?: number;
  empty?: string;
  /** When set, each line opens its journal entry. */
  onOpen?: (journalEntryId: string) => void;
  openEntryId?: string | null;
}

/**
 * Lines of the book on ruled paper. A real table, so a screen reader announces each amount with
 * its column — "Money out, 50.00" — and every row sits exactly on a printed rule.
 */
export function LedgerTable({
  caption,
  lines,
  variant = 'plain',
  broughtForwardMinor,
  carriedForwardMinor,
  empty,
  onOpen,
  openEntryId,
}: Props) {
  const book = variant === 'book';
  const folded = useMediaQuery(FOLDED);
  const columns = figureColumns(book, folded);

  return (
    <div className={styles.ruled}>
      <table className={`${styles.table} ${book ? styles.book : ''} ${folded ? styles.folded : ''}`}>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className={styles.date}>
              Date
            </th>
            <th scope="col">Particulars</th>
            {columns.map((column) => (
              <th key={column.key} scope="col" className={styles.amount}>
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {broughtForwardMinor !== undefined && lines.length > 0 && (
            <tr>
              <th scope="row" colSpan={2} className={styles.carried}>
                Balance <abbr title="brought forward">b/f</abbr>
              </th>
              <BalanceCells columns={columns} book={book} minor={broughtForwardMinor} />
            </tr>
          )}
          {lines.map((line) => {
            const open = openEntryId === line.journalEntryId;
            return (
              <tr key={line.lineId} className={open ? styles.open : undefined}>
                <td className={`${styles.date} figure`}>{lineDate(line.occurredAt)}</td>
                <td className={styles.particulars}>
                  {onOpen ? (
                    <button
                      type="button"
                      className={styles.opener}
                      aria-pressed={open}
                      onClick={() => onOpen(line.journalEntryId)}
                    >
                      {particulars(line)}
                    </button>
                  ) : (
                    <span className={styles.who}>{particulars(line)}</span>
                  )}
                  {/* The accountant's view is one line per entry; notes are read in Activity. */}
                  {line.memo && !book && <span className={styles.memo}>{line.memo}</span>}
                </td>
                {columns.map((column) => {
                  const cell = column.cell(line);
                  return (
                    <td
                      key={column.key}
                      className={`${styles.amount} ${cell.out ? styles.out : ''} ${cell.muted ? styles.running : ''} figure`}
                    >
                      {cell.text}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
        {carriedForwardMinor !== undefined && lines.length > 0 && (
          <tfoot>
            <tr>
              <th scope="row" colSpan={2} className={styles.carried}>
                Balance <abbr title="carried forward">c/f</abbr>
              </th>
              <BalanceCells columns={columns} book={book} minor={carriedForwardMinor} total />
            </tr>
          </tfoot>
        )}
      </table>
      {lines.length === 0 && empty && <p className={styles.empty}>{empty}</p>}
    </div>
  );
}

interface FigureColumn {
  key: string;
  header: ReactNode;
  cell: (line: StatementLine) => { text: string; out?: boolean; muted?: boolean };
}

const moneyIn = (line: StatementLine) => (line.amountMinor > 0 ? formatAmount(line.amountMinor) : '');
const moneyOut = (line: StatementLine) => (line.amountMinor < 0 ? formatAmount(line.amountMinor) : '');

/**
 * The figure columns for a view. Wide, money in and money out get a column each, as in a written
 * ledger. Folded onto a phone there is no room for both, so one signed column carries the amount
 * and its colour says which way it went.
 */
function figureColumns(book: boolean, folded: boolean): FigureColumn[] {
  const signed: FigureColumn = {
    key: 'amount',
    header: 'Amount',
    cell: (line) => ({ text: formatSigned(line.amountMinor), out: line.amountMinor < 0 }),
  };
  const balance: FigureColumn = {
    key: 'balance',
    header: 'Balance',
    cell: (line) => ({ text: formatAmount(line.balanceAfterMinor), muted: true }),
  };

  if (book) {
    return folded
      ? [signed, balance]
      : [
          {
            key: 'dr',
            header: <abbr title="Debit: money out of your wallet">Dr</abbr>,
            cell: (line) => ({ text: moneyOut(line), out: true }),
          },
          {
            key: 'cr',
            header: <abbr title="Credit: money into your wallet">Cr</abbr>,
            cell: (line) => ({ text: moneyIn(line) }),
          },
          balance,
        ];
  }
  return folded
    ? [signed]
    : [
        { key: 'in', header: 'Money in', cell: (line) => ({ text: moneyIn(line) }) },
        { key: 'out', header: 'Money out', cell: (line) => ({ text: moneyOut(line), out: true }) },
      ];
}

/**
 * A balance written across the figure columns. In the accountant's view it sits in the Balance
 * column, as a bookkeeper writes it; elsewhere it spans the amounts.
 */
function BalanceCells({
  columns,
  book,
  minor,
  total = false,
}: {
  columns: FigureColumn[];
  book: boolean;
  minor: number;
  total?: boolean;
}) {
  const figure = <span className={total ? styles.total : undefined}>{formatRupees(minor)}</span>;
  if (!book) {
    return (
      <td colSpan={columns.length} className={`${styles.amount} figure`}>
        {figure}
      </td>
    );
  }
  return (
    <>
      {columns.slice(0, -1).map((column) => (
        <td key={column.key} className={styles.amount} />
      ))}
      <td className={`${styles.amount} figure`}>{figure}</td>
    </>
  );
}
