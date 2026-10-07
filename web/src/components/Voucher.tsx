import { useState, type ReactNode } from 'react';
import { amountInWords } from '../lib/amounts';
import { formatAmount } from '../lib/money';
import { Stamp } from './Stamp';
import styles from './Voucher.module.css';

interface Props {
  title: string;
  /** "Pay to" or "Credit to". */
  partyLabel: string;
  party: ReactNode;
  amountMinor: number | null;
  being: string | null;
  signedBy: string | null;
  number?: string;
  stamp?: { label: string; at: Date };
  /** Marks a voucher whose payment was refused. Mutually exclusive with a stamp. */
  voided?: boolean;
}

/**
 * A printed payment voucher whose blanks fill in as the form on the facing page is completed —
 * figures, the amount in words as on a cheque, and finally the stamp.
 */
export function Voucher({
  title,
  partyLabel,
  party,
  amountMinor,
  being,
  signedBy,
  number,
  stamp,
  voided,
}: Props) {
  // Dated when the voucher is first drawn, not on every keystroke.
  const [today] = useState(() =>
    new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date()),
  );

  return (
    <figure className={styles.voucher} aria-label={title}>
      <header className={styles.head}>
        <p className={styles.title}>{title}</p>
        <dl className={styles.meta}>
          <div>
            <dt>No.</dt>
            <dd className="figure">{number ?? '—'}</dd>
          </div>
          <div>
            <dt>Date</dt>
            <dd className="figure">{today}</dd>
          </div>
        </dl>
      </header>

      <dl className={styles.blanks}>
        <div className={styles.blank}>
          <dt>{partyLabel}</dt>
          <dd>{party ?? <span className={styles.empty}>&nbsp;</span>}</dd>
        </div>
        <div className={styles.blank}>
          <dt>The sum of</dt>
          <dd className={styles.words}>
            {amountMinor ? amountInWords(amountMinor) : <span className={styles.empty}>&nbsp;</span>}
          </dd>
        </div>
        <div className={styles.blank}>
          <dt>Being</dt>
          <dd>{being || <span className={styles.empty}>&nbsp;</span>}</dd>
        </div>
      </dl>

      <footer className={styles.foot}>
        <p className={styles.figures}>
          <span className={styles.rupee}>₹</span>
          <span className="figure">{amountMinor ? formatAmount(amountMinor) : ''}</span>
        </p>
        {/* The space a printed voucher leaves for the office seal. */}
        <div className={styles.seal}>{stamp && <Stamp label={stamp.label} at={stamp.at} />}</div>
        <p className={styles.signature}>
          <span className={`${styles.sign} figure`}>{signedBy ?? ''}</span>
          <span className={styles.signLabel}>Authorised by</span>
        </p>
      </footer>

      {voided && (
        <div className={styles.void}>
          <span className={styles.voidBand}>REFUSED</span>
        </div>
      )}
    </figure>
  );
}
