import { Link, useSearchParams } from 'react-router';
import { useSentTransfers, useStatementPage } from '../api/queries';
import { Book } from '../components/Book';
import { EntryCard } from '../components/EntryCard';
import { LedgerTable } from '../components/LedgerTable';
import { PageTurner } from '../components/PageTurner';
import { Sheet } from '../components/Sheet';
import { pageSpan } from '../lib/entries';
import { formatAmount } from '../lib/money';
import { payee, transferState } from '../lib/transfers';
import { usePageSize } from '../lib/usePageSize';
import { usePageTurns } from '../lib/usePageTurns';
import { FOLDED, useMediaQuery } from '../lib/useMediaQuery';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';

/**
 * Everything that has happened to the money, a page at a time. The right page lists it; choosing
 * a line opens that line's journal entry on the left page. The open entry and the page on show
 * both live in the address, so either can be linked to.
 */
export function Activity() {
  const [params, setParams] = useSearchParams();
  const openEntry = params.get('entry');
  const turns = usePageTurns();
  // Sized so the page fits the window even if every entry carries a note on a second line.
  const pageSize = usePageSize(2);
  const statement = useStatementPage(pageSize, turns.cursor);
  const lines = statement.data?.lines ?? [];
  const nextCursor = statement.data?.nextCursor;

  // On a phone there is no facing page, so the entry slides up in a sheet instead.
  const folded = useMediaQuery(FOLDED);

  const setEntry = (id: string | null) =>
    setParams(
      (current) => {
        if (id) {
          current.set('entry', id);
        } else {
          current.delete('entry');
        }
        return current;
      },
      // Opening an entry must not lose the way back through the pages.
      { state: turns.state },
    );
  const closeEntry = () => setEntry(null);

  const left =
    openEntry && !folded ? (
      <div className={page.column}>
        <EntryCard journalEntryId={openEntry} />
        <p className={page.footnote}>
          <button type="button" className={formStyles.link} onClick={closeEntry}>
            Close this entry
          </button>
        </p>
      </div>
    ) : (
      <ActivityIntro />
    );

  const right = (
    <div className={page.column}>
      <header className={page.pageHeader}>
        <h2 className={page.pageHeading}>All activity</h2>
        <p className={page.span}>{pageSpan(lines)}</p>
      </header>
      <LedgerTable
        caption={`Activity, newest first: ${pageSpan(lines)}`}
        lines={lines}
        empty={statement.isSuccess ? 'Nothing written yet.' : undefined}
        onOpen={setEntry}
        openEntryId={openEntry}
      />
      <PageTurner
        onEarlier={nextCursor ? () => turns.earlier(nextCursor) : undefined}
        onLater={turns.later}
        busy={statement.isFetching}
      />
    </div>
  );

  return (
    <>
      <Book label="Activity" left={left} right={right} paged />
      {folded && (
        <Sheet open={openEntry !== null} onClose={closeEntry} title="Journal entry">
          {openEntry && <EntryCard journalEntryId={openEntry} />}
        </Sheet>
      )}
    </>
  );
}

/** The left page before an entry is chosen: how to read this page, and any payment not yet paid. */
function ActivityIntro() {
  const sent = useSentTransfers(30);
  const unfinished = (sent.data?.transfers ?? []).filter((t) => t.status !== 'COMPLETED');

  return (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Activity</p>
        <h1 className={page.title}>Every rupee in and out</h1>
      </header>
      <p className={page.lead}>
        Choose any line to open its journal entry and see both sides of it: where the money left, where it
        arrived, and that the two match exactly.
      </p>

      {unfinished.length > 0 && (
        <section aria-labelledby="unfinished-label">
          <h2 id="unfinished-label" className={page.sectionLabel}>
            Payments that did not go through, or are still settling
          </h2>
          <ul className={page.list}>
            {unfinished.map((transfer) => (
              <li key={transfer.transferId} className={page.listRow}>
                <span>
                  <Link to={`/transfers/${transfer.transferId}`}>To {payee(transfer)}</Link>
                  <br />
                  <span className={page.muted}>{transferState(transfer)}</span>
                </span>
                <span className="figure">{formatAmount(transfer.amountMinor)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <p className={page.footnote}>
        Refused payments never reach the ledger, so they appear only here. Money you receive appears on the
        right as soon as it lands.
      </p>
    </div>
  );
}
