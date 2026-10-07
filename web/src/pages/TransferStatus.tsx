import { Link, useParams } from 'react-router';
import { useMe, useTransfer } from '../api/queries';
import { Book } from '../components/Book';
import { Voucher } from '../components/Voucher';
import { formatRupees } from '../lib/money';
import { payee, transferState } from '../lib/transfers';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';

/**
 * Where a payment whose outcome is not yet known goes. The server answered 202: it recorded the
 * request but could not confirm the ledger posting before replying. This page checks back every
 * few seconds until the reconciliation sweep settles it either way.
 */
export function TransferStatus() {
  const { transferId = '' } = useParams();
  const me = useMe();
  const transfer = useTransfer(transferId);

  if (transfer.isError) {
    return (
      <Book
        label="Payment"
        left={
          <div className={page.column}>
            <h1 className={page.title}>No such payment</h1>
            <p className={page.lead}>There is no payment of yours with that reference.</p>
            <Link to="/activity">Back to activity</Link>
          </div>
        }
        right={null}
      />
    );
  }

  const t = transfer.data;
  const settling = t && (t.status === 'PENDING' || t.status === 'NEEDS_RECONCILIATION');

  const left = (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Payment</p>
        <h1 className={page.title}>{t ? `To ${payee(t)}` : 'Reading…'}</h1>
      </header>
      {t && (
        <>
          <p className={`figure ${page.bigFigure}`}>{formatRupees(t.amountMinor)}</p>
          <p className={page.confirm} role="status" aria-live="polite">
            {transferState(t)}
          </p>
          {settling && (
            <p className={page.lead}>
              The ledger did not answer in time, so this payment is being confirmed rather than guessed at. It
              will either complete or be safely retried; it cannot happen twice. This page checks again every
              few seconds.
            </p>
          )}
          {t.status === 'FAILED' && (
            <p className={page.lead}>
              No money left your wallet. You can try again once the reason is resolved.
            </p>
          )}
        </>
      )}
      <div className={formStyles.actions}>
        <Link to="/" className={formStyles.primary}>
          Back to the book
        </Link>
        <Link to="/activity" className={formStyles.secondary}>
          Activity
        </Link>
      </div>
    </div>
  );

  const right = t ? (
    <Voucher
      title="Payment voucher"
      partyLabel="Pay to"
      party={payee(t)}
      amountMinor={t.amountMinor}
      being={t.note}
      signedBy={me.data ? `@${me.data.handle}` : null}
      number={t.transferId.slice(0, 8)}
      stamp={
        t.status === 'COMPLETED' ? { label: 'PAID', at: new Date(t.completedAt ?? t.createdAt) } : undefined
      }
      voided={t.status === 'FAILED'}
    />
  ) : null;

  return <Book label="Payment" left={left} right={right} />;
}
