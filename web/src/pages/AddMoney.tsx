import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { useTopUp } from '../api/queries';
import type { TopUpResult } from '../api/types';
import { Book } from '../components/Book';
import { Field, FormError } from '../components/Form';
import { JournalPreview } from '../components/JournalPreview';
import { Voucher } from '../components/Voucher';
import { parseRupees } from '../lib/amounts';
import { formatRupees } from '../lib/money';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';

/** The most one top-up may add — mirrors wallet-service's ceiling. */
const PER_TOP_UP_LIMIT = 10_000_000;
const PRESETS = [50_000, 100_000, 500_000, 1_000_000];

/**
 * Adding test money. Labelled as exactly what it is: the backend debits an internal funding
 * account standing in for a bank, and no real money is involved anywhere.
 */
export function AddMoney() {
  const topUp = useTopUp();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ topUp: TopUpResult; at: Date } | null>(null);
  // One key per amount. Retrying the same amount after a dropped connection reuses it, so the
  // money cannot be added twice; choosing a different amount is a different top-up.
  const [attempt, setAttempt] = useState<{ amountMinor: number; key: string } | null>(null);

  const amountMinor = parseRupees(text);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (amountMinor === null || amountMinor <= 0) {
      setError('Write the amount in rupees, for example 1000.');
      return;
    }
    if (amountMinor > PER_TOP_UP_LIMIT) {
      setError(`One top-up can add at most ${formatRupees(PER_TOP_UP_LIMIT)}.`);
      return;
    }
    const current =
      attempt?.amountMinor === amountMinor ? attempt : { amountMinor, key: crypto.randomUUID() };
    setAttempt(current);
    try {
      setResult({
        topUp: await topUp.mutateAsync({ amountMinor, idempotencyKey: current.key }),
        at: new Date(),
      });
    } catch (failure) {
      setError(
        failure instanceof ApiError
          ? failure.message
          : 'The connection dropped before an answer came back. Trying again is safe: it cannot add the money twice.',
      );
    }
  }

  function again() {
    setResult(null);
    setAttempt(null);
    setText('');
  }

  const left = (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Add test money</p>
        <h1 className={page.title}>{result ? 'Added' : 'How much test money?'}</h1>
      </header>
      <p className={page.lead}>
        This is a sandbox. Test money comes from the platform’s funding account, which stands in for a bank;
        no real money moves, and none can be withdrawn.
      </p>

      {result ? (
        <div className={page.form} role="status">
          <p>
            Your balance is now <strong className="figure">{formatRupees(result.topUp.balanceMinor)}</strong>.
          </p>
          <div className={formStyles.actions}>
            <Link to="/" className={formStyles.primary}>
              Back to the book
            </Link>
            <button type="button" className={formStyles.secondary} onClick={again}>
              Add more
            </button>
          </div>
        </div>
      ) : (
        <form className={page.form} onSubmit={submit} noValidate>
          <ul className={formStyles.chips} aria-label="Common amounts">
            {PRESETS.map((preset) => (
              <li key={preset}>
                <button
                  type="button"
                  className={formStyles.chip}
                  aria-pressed={amountMinor === preset}
                  onClick={() => setText(String(preset / 100))}
                >
                  {formatRupees(preset)}
                </button>
              </li>
            ))}
          </ul>
          <Field
            label="Amount"
            name="amount"
            prefix="₹"
            large
            inputMode="decimal"
            autoComplete="off"
            value={text}
            onChange={(event) => setText(event.target.value)}
            hint={`Up to ${formatRupees(PER_TOP_UP_LIMIT)} at a time.`}
          />
          {error && <FormError>{error}</FormError>}
          <div className={formStyles.actions}>
            <button type="submit" className={formStyles.primary} disabled={topUp.isPending}>
              {topUp.isPending ? 'Adding…' : 'Add test money'}
            </button>
          </div>
        </form>
      )}
    </div>
  );

  const right = (
    <div className={page.column}>
      <Voucher
        title="Credit voucher"
        partyLabel="Credit to"
        party="Your wallet"
        amountMinor={amountMinor && amountMinor > 0 ? amountMinor : null}
        being="Test money from the funding account"
        signedBy="sandbox"
        number={result?.topUp.journalEntryId.slice(0, 8)}
        stamp={result ? { label: 'CREDITED', at: result.at } : undefined}
      />
      {amountMinor !== null && amountMinor > 0 && (
        <JournalPreview
          posted={result !== null}
          lines={[
            { account: 'Funding account (test money)', amountMinor: -amountMinor },
            { account: 'Your wallet', amountMinor },
          ]}
        />
      )}
    </div>
  );

  return <Book label="Add test money" left={left} right={right} />;
}
