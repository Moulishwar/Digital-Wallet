import { Link, useNavigate } from 'react-router';
import { recentPeople, useMe, useRecentLines, useWallet } from '../api/queries';
import { Book } from '../components/Book';
import { LedgerTable } from '../components/LedgerTable';
import { APP_NAME } from '../config';
import { pageDate } from '../lib/entries';
import { formatAmount, spokenRupees } from '../lib/money';
import formStyles from '../components/Form.module.css';
import styles from './Home.module.css';

const RECENT_LINES = 7;

export function Home() {
  return <Book label={`${APP_NAME}: home`} left={<AccountPage />} right={<RecentEntriesPage />} />;
}

/** The left page: whose book this is, what is in it, and what you can do. */
function AccountPage() {
  const me = useMe();
  const wallet = useWallet();
  const lines = useRecentLines(50);
  const people = recentPeople(lines.data?.lines ?? [], 5);

  return (
    <div className={styles.account}>
      <header className={styles.owner}>
        <p className={styles.wordmark}>{APP_NAME}</p>
        <p className={styles.accountOf}>Account of</p>
        <h1 className={styles.name}>{me.data?.fullName ?? ' '}</h1>
        {me.data && <p className={`${styles.handle} figure`}>@{me.data.handle}</p>}
      </header>

      <section className={styles.balance} aria-labelledby="balance-label">
        <h2 id="balance-label" className={styles.balanceLabel}>
          Balance
        </h2>
        {wallet.data ? (
          <p className={`${styles.balanceFigure} figure`} aria-label={spokenRupees(wallet.data.balanceMinor)}>
            <span className={styles.rupee}>₹</span>
            <span className={styles.total}>{formatAmount(wallet.data.balanceMinor)}</span>
          </p>
        ) : (
          <p className={`${styles.balanceFigure} figure`} aria-busy="true">
            <span className={styles.pending}>—</span>
          </p>
        )}
      </section>

      <div className={styles.actions}>
        <Link to="/send" className={formStyles.primary}>
          Send money
        </Link>
        <Link to="/add" className={formStyles.secondary}>
          Add test money
        </Link>
      </div>
      <p className={styles.sandbox}>Test money only. No real bank is involved.</p>

      {people.length > 0 && (
        <section className={styles.people} aria-labelledby="people-label">
          <h2 id="people-label" className={styles.sectionLabel}>
            Recently paid and paid by
          </h2>
          <ul>
            {people.map((person) => (
              <li key={person.handle}>
                <Link to={`/send?to=${encodeURIComponent(person.handle)}`} className={styles.person}>
                  <span>{person.name ?? `@${person.handle}`}</span>
                  {person.name && <span className={`${styles.personHandle} figure`}>@{person.handle}</span>}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <aside className={styles.howItWorks}>
        <p>
          Every rupee here is written twice: once where it leaves, once where it arrives, and the two always
          add up to zero. <Link to="/ledger">See both sides in the ledger</Link>
        </p>
      </aside>
    </div>
  );
}

/** The right page: the newest lines of the book, on ruled paper. */
function RecentEntriesPage() {
  const navigate = useNavigate();
  const wallet = useWallet();
  const lines = useRecentLines(RECENT_LINES);

  return (
    <div className={styles.entries}>
      <header className={styles.entriesHeader}>
        <h2 className={styles.entriesTitle}>Recent entries</h2>
        <p className={styles.pageDate}>{pageDate()}</p>
      </header>

      <LedgerTable
        caption="Recent entries"
        lines={lines.data?.lines ?? []}
        carriedForwardMinor={wallet.data?.balanceMinor}
        empty={lines.isSuccess ? 'Nothing written yet. Add test money to open the account.' : undefined}
        onOpen={(id) => navigate(`/activity?entry=${id}`)}
      />

      <Link to="/activity" className={styles.more}>
        Turn to all activity
      </Link>
    </div>
  );
}
