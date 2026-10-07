import { useReconciliation } from '../api/queries';
import { Book } from '../components/Book';
import { formatRupees } from '../lib/money';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';
import styles from './Health.module.css';

const checkedAt = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit', second: '2-digit' });

/**
 * The auditor's page. Runs the live reconciliation over the whole platform's ledger: every cached
 * balance against the sum of its own lines, and the signed total of every line against zero.
 */
export function Health() {
  const check = useReconciliation();
  const result = check.data;

  const left = (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Ledger health</p>
        <h1 className={page.title}>Does the whole book balance?</h1>
      </header>
      <p className={page.lead}>
        Two independent checks over every account on the platform, run against live data each time you ask.
      </p>
      <ol className={styles.explain}>
        <li>
          <strong>No balance has drifted.</strong> Each wallet keeps its balance written down for speed. The
          check adds up that wallet’s own ledger lines from scratch and compares.
        </li>
        <li>
          <strong>Nothing was created or lost.</strong> Every movement is written twice, once as money out and
          once as money in, so the signed total of every line ever written must be exactly zero.
        </li>
      </ol>
      <div className={formStyles.actions}>
        <button
          type="button"
          className={formStyles.primary}
          onClick={() => check.refetch()}
          disabled={check.isFetching}
        >
          {check.isFetching ? 'Checking…' : 'Check again'}
        </button>
        {check.dataUpdatedAt > 0 && (
          <span className={page.muted}>Last checked at {checkedAt.format(check.dataUpdatedAt)}</span>
        )}
      </div>
    </div>
  );

  const right = (
    <div className={page.column}>
      <h2 className={page.pageHeading}>Auditor’s certificate</h2>
      {check.isError && <p className={page.muted}>The check could not be run. Only auditors may run it.</p>}
      {result && (
        <section className={styles.certificate} aria-live="polite">
          <p className={result.consistent ? styles.verdictGood : styles.verdictBad}>
            {result.consistent ? 'The ledger is consistent.' : 'The ledger is NOT consistent.'}
          </p>
          <dl className={styles.findings}>
            <div>
              <dt>Balances that differ from their lines</dt>
              <dd className="figure">{result.accountsWithDriftedBalance.length}</dd>
            </div>
            <div>
              <dt>Signed total of every line</dt>
              <dd className="figure">{formatRupees(result.ledgerSumMinor)}</dd>
            </div>
          </dl>
          {result.accountsWithDriftedBalance.length > 0 && (
            <ul className={`${styles.drifted} figure`}>
              {result.accountsWithDriftedBalance.map((id) => (
                <li key={id}>{id}</li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );

  return <Book label="Ledger health" left={left} right={right} />;
}
