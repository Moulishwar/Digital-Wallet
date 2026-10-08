import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError, api } from '../api/client';
import { recentPeople, useMe, useRecentLines, useSendMoney, useWallet } from '../api/queries';
import type { Transfer, UserLookup } from '../api/types';
import { Book } from '../components/Book';
import { Field, FormError } from '../components/Form';
import { JournalPreview } from '../components/JournalPreview';
import { Voucher } from '../components/Voucher';
import { useDemo } from '../demo';
import { parseRupees } from '../lib/amounts';
import { formatRupees } from '../lib/money';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';

type Step = 'who' | 'amount' | 'review' | 'done';

/** One ruled line of a statement — mirrors transfer-service's limit on a note. */
const NOTE_LIMIT = 30;

/** The most one payment may move — mirrors transfer-service's ceiling. */
const PER_PAYMENT_LIMIT = 10_000_000;

const STEPS: { id: Exclude<Step, 'done'>; label: string }[] = [
  { id: 'who', label: 'Who' },
  { id: 'amount', label: 'How much' },
  { id: 'review', label: 'Review' },
];

/**
 * Who → how much → review. The idempotency key is made when the review is shown and kept until
 * the payment settles, so a double tap, a retry after a dropped connection, or a refresh of the
 * button can only ever pay once.
 */
export function Send() {
  const [params] = useSearchParams();
  const me = useMe();
  const [step, setStep] = useState<Step>('who');
  const [recipient, setRecipient] = useState<UserLookup | null>(null);
  const [amountMinor, setAmountMinor] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [result, setResult] = useState<Transfer | null>(null);

  const left = (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Send money</p>
        <h1 className={page.title}>
          {step === 'done' ? 'Paid' : recipient ? `Paying ${recipient.fullName}` : 'Who are you paying?'}
        </h1>
      </header>

      {step !== 'done' && (
        <ol className={page.steps} aria-label="Steps">
          {STEPS.map((s) => (
            <li key={s.id} aria-current={s.id === step ? 'step' : undefined}>
              {s.label}
            </li>
          ))}
        </ol>
      )}

      {step === 'who' && (
        <WhoStep
          initialHandle={params.get('to') ?? ''}
          ownHandle={me.data?.handle}
          onChosen={(found) => {
            setRecipient(found);
            setStep('amount');
          }}
        />
      )}
      {step === 'amount' && recipient && (
        <AmountStep
          initialAmount={amountMinor}
          initialNote={note}
          onBack={() => setStep('who')}
          onDone={(amount, written) => {
            setAmountMinor(amount);
            setNote(written);
            setStep('review');
          }}
        />
      )}
      {step === 'review' && recipient && amountMinor && (
        <ReviewStep
          recipient={recipient}
          amountMinor={amountMinor}
          note={note}
          onChange={() => setStep('amount')}
          onPaid={(transfer) => {
            setResult(transfer);
            setStep('done');
          }}
        />
      )}
      {step === 'done' && result && <DoneStep transfer={result} />}
    </div>
  );

  const right = (
    <div className={page.column}>
      <Voucher
        title="Payment voucher"
        partyLabel="Pay to"
        party={recipient ? `${recipient.fullName} (@${recipient.handle})` : null}
        amountMinor={amountMinor}
        being={note || null}
        signedBy={me.data ? `@${me.data.handle}` : null}
        number={result?.transferId.slice(0, 8)}
        stamp={
          result?.status === 'COMPLETED'
            ? { label: 'PAID', at: new Date(result.completedAt ?? result.createdAt) }
            : undefined
        }
      />
      {recipient && amountMinor && (step === 'review' || step === 'done') && (
        <JournalPreview
          posted={step === 'done'}
          lines={[
            { account: 'Your wallet', amountMinor: -amountMinor },
            { account: `${recipient.fullName}’s wallet`, amountMinor },
          ]}
        />
      )}
    </div>
  );

  return <Book label="Send money" left={left} right={right} />;
}

function canonical(handle: string): string {
  return handle.trim().replace(/^@/, '').toLowerCase();
}

function WhoStep({
  initialHandle,
  ownHandle,
  onChosen,
}: {
  initialHandle: string;
  ownHandle: string | undefined;
  onChosen: (recipient: UserLookup) => void;
}) {
  const lines = useRecentLines(50);
  const people = recentPeople(lines.data?.lines ?? [], 6);
  // In the demo, the shared accounts are always there to be paid, even from a brand-new account.
  const demoPeople = (useDemo()?.accounts ?? []).filter(
    (account) =>
      account.role === 'person' &&
      account.handle !== ownHandle &&
      !people.some((person) => person.handle === account.handle),
  );
  const [handle, setHandle] = useState(initialHandle);
  // The handle being looked up. Starts filled when arriving from a QR code or a "pay again" link,
  // so that person is looked up straight away.
  const [asked, setAsked] = useState(canonical(initialHandle));
  const isSelf = asked !== '' && asked === ownHandle;

  const lookup = useQuery({
    queryKey: ['lookup', asked],
    queryFn: () => api<UserLookup>(`/api/users/lookup?handle=${encodeURIComponent(asked)}`),
    enabled: asked !== '' && !isSelf,
    retry: false,
  });

  const error = isSelf
    ? 'That is your own handle. You cannot pay yourself.'
    : lookup.error instanceof ApiError && lookup.error.status === 404
      ? `No one has the handle @${asked}. Check the spelling with them.`
      : lookup.error
        ? 'Could not look that up right now. Try again.'
        : null;
  const found = asked !== '' && !isSelf ? lookup.data : undefined;

  return (
    <div className={page.form}>
      <form
        className={page.form}
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          setAsked(canonical(handle));
        }}
      >
        <Field
          label="Their handle"
          name="handle"
          prefix="@"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          value={handle}
          onChange={(event) => setHandle(event.target.value)}
        />
        <div className={formStyles.actions}>
          <button
            type="submit"
            className={formStyles.secondary}
            disabled={lookup.isFetching || !handle.trim()}
          >
            {lookup.isFetching ? 'Looking…' : 'Find'}
          </button>
        </div>
      </form>

      {!found && (
        <>
          <Suggestions
            id="recent-label"
            title="Recent people"
            people={people.map((person) => ({
              handle: person.handle,
              label: person.name ?? `@${person.handle}`,
            }))}
            onPick={(picked) => {
              setHandle(picked);
              setAsked(picked);
            }}
          />
          <Suggestions
            id="demo-people-label"
            title="People in this demo"
            people={demoPeople.map((account) => ({ handle: account.handle, label: account.fullName }))}
            onPick={(picked) => {
              setHandle(picked);
              setAsked(picked);
            }}
          />
        </>
      )}

      {error && <FormError>{error}</FormError>}

      {found && (
        <div className={page.confirm} role="status">
          <p className={page.confirmName}>{found.fullName}</p>
          <p className="figure">@{found.handle}</p>
          <div className={`${formStyles.actions} ${page.spaced}`}>
            <button type="button" className={formStyles.primary} onClick={() => onChosen(found)}>
              Yes, pay {found.fullName.split(' ')[0]}
            </button>
            <button type="button" className={formStyles.link} onClick={() => setAsked('')}>
              Not them
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** People to pay with one tap, by name. */
function Suggestions({
  id,
  title,
  people,
  onPick,
}: {
  id: string;
  title: string;
  people: { handle: string; label: string }[];
  onPick: (handle: string) => void;
}) {
  if (people.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className={page.sectionLabel}>
        {title}
      </h2>
      <ul className={`${formStyles.chips} ${page.spaced}`}>
        {people.map((person) => (
          <li key={person.handle}>
            <button type="button" className={formStyles.chip} onClick={() => onPick(person.handle)}>
              {person.label}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function AmountStep({
  initialAmount,
  initialNote,
  onBack,
  onDone,
}: {
  initialAmount: number | null;
  initialNote: string;
  onBack: () => void;
  onDone: (amountMinor: number, note: string) => void;
}) {
  const wallet = useWallet();
  const [text, setText] = useState(initialAmount ? (initialAmount / 100).toFixed(2) : '');
  const [note, setNote] = useState(initialNote);
  const [error, setError] = useState<string | null>(null);
  const balance = wallet.data?.balanceMinor;

  function submit(event: FormEvent) {
    event.preventDefault();
    const amount = parseRupees(text);
    if (amount === null || amount <= 0) {
      setError('Write the amount in rupees, for example 450 or 450.50.');
    } else if (amount > PER_PAYMENT_LIMIT) {
      setError(`One payment can move at most ${formatRupees(PER_PAYMENT_LIMIT)}.`);
    } else if (balance !== undefined && amount > balance) {
      setError(`That is more than your balance of ${formatRupees(balance)}.`);
    } else {
      onDone(amount, note.trim());
    }
  }

  return (
    <form className={page.form} onSubmit={submit} noValidate>
      <Field
        label="Amount"
        name="amount"
        prefix="₹"
        large
        inputMode="decimal"
        autoComplete="off"
        value={text}
        onChange={(event) => setText(event.target.value)}
        hint={balance !== undefined ? `Your balance is ${formatRupees(balance)}.` : undefined}
        autoFocus
      />
      <Field
        label="Note (optional)"
        name="note"
        maxLength={NOTE_LIMIT}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        hint={`Both of you will see it. ${NOTE_LIMIT - note.length} of ${NOTE_LIMIT} characters left.`}
      />
      {error && <FormError>{error}</FormError>}
      <div className={formStyles.actions}>
        <button type="submit" className={formStyles.primary}>
          Review
        </button>
        <button type="button" className={formStyles.link} onClick={onBack}>
          Choose someone else
        </button>
      </div>
    </form>
  );
}

function ReviewStep({
  recipient,
  amountMinor,
  note,
  onChange,
  onPaid,
}: {
  recipient: UserLookup;
  amountMinor: number;
  note: string;
  onChange: () => void;
  onPaid: (transfer: Transfer) => void;
}) {
  const navigate = useNavigate();
  const send = useSendMoney();
  // One key for this exact payment, made once. Every retry of it presents the same key.
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [uncertain, setUncertain] = useState(false);

  async function pay() {
    setUncertain(false);
    try {
      const transfer = await send.mutateAsync({
        recipientHandle: recipient.handle,
        amountMinor,
        note: note || null,
        idempotencyKey,
      });
      if (transfer.status === 'COMPLETED') {
        onPaid(transfer);
      } else {
        // Accepted, outcome not yet known: the status page follows it to the end.
        navigate(`/transfers/${transfer.transferId}`);
      }
    } catch (failure) {
      if (!(failure instanceof ApiError)) {
        setUncertain(true);
      }
    }
  }

  const failure = send.error instanceof ApiError ? send.error : null;

  return (
    <div className={page.form}>
      <p className={page.confirm}>
        Pay <strong>{recipient.fullName}</strong>{' '}
        <strong className="figure">{formatRupees(amountMinor)}</strong>
        {note ? <> for “{note}”</> : null}.
      </p>

      {failure && <FormError>{failure.message}</FormError>}
      {uncertain && (
        <FormError>
          The connection dropped before an answer came back, so we cannot yet say whether it went through.
          Paying again is safe: this exact payment can only ever be made once.
        </FormError>
      )}

      <div className={formStyles.actions}>
        {/* A refusal is final for this key — retrying replays the same answer — so the way on is
            to change the payment, which makes a new one. */}
        <button
          type="button"
          className={formStyles.primary}
          onClick={pay}
          disabled={send.isPending || failure !== null}
        >
          {send.isPending ? 'Paying…' : uncertain ? 'Try again' : `Pay ${formatRupees(amountMinor)}`}
        </button>
        <button type="button" className={formStyles.link} onClick={onChange} disabled={send.isPending}>
          Change the amount
        </button>
      </div>
    </div>
  );
}

function DoneStep({ transfer }: { transfer: Transfer }) {
  return (
    <div className={page.form} role="status">
      <p>
        {formatRupees(transfer.amountMinor)} went to{' '}
        {transfer.recipientName ?? `@${transfer.recipientHandle}`}.
      </p>
      {transfer.senderBalanceAfterMinor !== null && (
        <p className={page.muted}>
          Your balance is now <span className="figure">{formatRupees(transfer.senderBalanceAfterMinor)}</span>
          .
        </p>
      )}
      <div className={formStyles.actions}>
        <Link to="/" className={formStyles.primary}>
          Back to the book
        </Link>
        <Link to="/activity" className={formStyles.secondary}>
          See it in activity
        </Link>
      </div>
    </div>
  );
}
