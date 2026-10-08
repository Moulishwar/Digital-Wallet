import { useState, type FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import { useAuth } from '../auth/context';
import { Field, FormError } from '../components/Form';
import { APP_NAME } from '../config';
import { useDemo, type Demo, type DemoAccount } from '../demo';
import formStyles from '../components/Form.module.css';
import styles from './Cover.module.css';

type Mode = 'signIn' | 'register';

const CLOSED_NOTICE = {
  idle: 'Your book closed itself after 15 minutes without activity. Sign in to open it again.',
  expired: 'Your session ended. Sign in to open your book again.',
};

/**
 * The closed book. Signed out, the app is a red cloth cover tied with a gold cord, and the label
 * plate on its front is where you sign in or open an account.
 */
export function Cover({ checking = false }: { checking?: boolean }) {
  const { closedReason } = useAuth();
  const [mode, setMode] = useState<Mode>('signIn');
  const demo = useDemo();

  return (
    <main className={styles.cover}>
      <div className={styles.tie} aria-hidden="true">
        <span className={styles.cord} />
        <span className={styles.knot} />
        <span className={`${styles.end} ${styles.endA}`} />
        <span className={`${styles.end} ${styles.endB}`} />
      </div>

      <section className={styles.plate} aria-labelledby="cover-title">
        <h1 id="cover-title" className={styles.title}>
          {APP_NAME}
        </h1>
        <p className={styles.subtitle}>A wallet kept like an account book: every rupee written twice.</p>

        {closedReason && !checking && (
          <p className={styles.notice} role="status">
            {CLOSED_NOTICE[closedReason]}
          </p>
        )}

        {checking ? (
          <p className={styles.checking} role="status">
            Opening your book…
          </p>
        ) : mode === 'signIn' ? (
          <>
            {demo && <DemoAccounts demo={demo} />}
            <SignInForm onSwitch={() => setMode('register')} />
          </>
        ) : (
          <RegisterForm onSwitch={() => setMode('signIn')} />
        )}
      </section>
    </main>
  );
}

function failureMessage(failure: unknown): string {
  return failure instanceof ApiError ? failure.message : 'Could not reach the server. Try again.';
}

/**
 * The demo's shared accounts, each opened with one tap. Two people, so a visitor can pay one
 * and then open the other's book to see the money arrive, and an auditor for the ledger health page.
 */
function DemoAccounts({ demo }: { demo: Demo }) {
  const { signIn } = useAuth();
  const [opening, setOpening] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function open(account: DemoAccount) {
    setOpening(account.handle);
    setError(null);
    try {
      await signIn(account.email, account.password);
    } catch (failure) {
      setError(failureMessage(failure));
      setOpening(null);
    }
  }

  return (
    <section className={styles.demo} aria-labelledby="demo-title">
      <h2 id="demo-title" className={styles.demoTitle}>
        Try the demo
      </h2>
      <p className={styles.demoNote}>
        Open a shared account with one tap: pay one person, then open the other’s book to see it arrive. Test
        money only{demo.resetsAt ? `, wiped back to the start at ${demo.resetsAt} each night` : ''}.
      </p>
      <ul className={styles.demoAccounts}>
        {demo.accounts.map((account) => (
          <li key={account.handle} className={account.role === 'auditor' ? styles.demoAuditor : undefined}>
            <button
              type="button"
              className={formStyles.secondary}
              onClick={() => open(account)}
              disabled={opening !== null}
            >
              {opening === account.handle
                ? 'Opening…'
                : account.role === 'auditor'
                  ? `${account.fullName}: ledger health`
                  : account.fullName}
            </button>
          </li>
        ))}
      </ul>
      {error && <FormError>{error}</FormError>}
      <p className={styles.demoOr}>or use your own account</p>
    </section>
  );
}

function SignInForm({ onSwitch }: { onSwitch: () => void }) {
  const { signIn } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      await signIn(String(form.get('email')), String(form.get('password')));
    } catch (failure) {
      setError(failureMessage(failure));
      setBusy(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field label="Password" name="password" type="password" autoComplete="current-password" required />
      {error && <FormError>{error}</FormError>}
      <button className={formStyles.primary} type="submit" disabled={busy}>
        {busy ? 'Opening…' : 'Open my book'}
      </button>
      <p className={styles.switch}>
        New here?{' '}
        <button type="button" className={formStyles.link} onClick={onSwitch}>
          Open an account
        </button>
      </p>
    </form>
  );
}

function RegisterForm({ onSwitch }: { onSwitch: () => void }) {
  const { signIn } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const email = String(form.get('email'));
    const password = String(form.get('password'));
    setBusy(true);
    setError(null);
    try {
      await api('/api/auth/register', {
        method: 'POST',
        anonymous: true,
        body: { fullName: form.get('fullName'), handle: form.get('handle'), email, password },
      });
      await signIn(email, password);
    } catch (failure) {
      setError(failureMessage(failure));
      setBusy(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <Field label="Your name" name="fullName" autoComplete="name" required />
      <Field
        label="Handle"
        name="handle"
        autoComplete="username"
        prefix="@"
        hint="How people find you to pay you: 3–32 letters, digits or _"
        required
      />
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field
        label="Password"
        name="password"
        type="password"
        autoComplete="new-password"
        hint="At least 12 characters. A short sentence works well."
        required
      />
      {error && <FormError>{error}</FormError>}
      <button className={formStyles.primary} type="submit" disabled={busy}>
        {busy ? 'Opening…' : 'Open my account'}
      </button>
      <p className={styles.switch}>
        Already have one?{' '}
        <button type="button" className={formStyles.link} onClick={onSwitch}>
          Sign in
        </button>
      </p>
    </form>
  );
}
