import QRCode from 'qrcode';
import { useEffect, useState } from 'react';
import { useMe } from '../api/queries';
import { IDLE_LIMIT_MS, useAuth } from '../auth/context';
import { Book } from '../components/Book';
import formStyles from '../components/Form.module.css';
import page from './Page.module.css';
import styles from './Profile.module.css';

export function Profile() {
  const me = useMe();
  const { signOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  const left = (
    <div className={page.column}>
      <header>
        <p className={page.kicker}>Profile</p>
        <h1 className={page.title}>{me.data?.fullName ?? ' '}</h1>
      </header>

      {me.data && (
        <dl className={styles.details}>
          <div>
            <dt>Handle</dt>
            <dd className="figure">@{me.data.handle}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd className="figure">{me.data.email}</dd>
          </div>
          <div>
            <dt>Account</dt>
            <dd>{me.data.roles.includes('ROLE_ADMIN') ? 'Member, with auditor access' : 'Member'}</dd>
          </div>
        </dl>
      )}

      <section aria-labelledby="session-label">
        <h2 id="session-label" className={page.sectionLabel}>
          This session
        </h2>
        <p className={page.muted}>
          Your book closes itself after {IDLE_LIMIT_MS / 60_000} minutes without activity. Closing it now ends
          this session on the server too.
        </p>
        <div className={`${formStyles.actions} ${page.spaced}`}>
          <button
            type="button"
            className={formStyles.secondary}
            disabled={signingOut}
            onClick={() => {
              setSigningOut(true);
              void signOut();
            }}
          >
            {signingOut ? 'Closing…' : 'Close my book'}
          </button>
        </div>
      </section>
    </div>
  );

  return (
    <Book label="Profile" left={left} right={me.data ? <ReceiveCard handle={me.data.handle} /> : null} />
  );
}

/**
 * A QR code for being paid. It encodes an ordinary link to the send screen with the handle filled
 * in, so a phone's own camera app opens it — no scanner inside this app is needed.
 */
function ReceiveCard({ handle }: { handle: string }) {
  const link = `${window.location.origin}/send?to=${encodeURIComponent(handle)}`;
  const [svg, setSvg] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    // Always dark modules on light paper, in both themes: cameras read that reliably.
    QRCode.toString(link, {
      type: 'svg',
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#1a1c22', light: '#f1f2ec' },
    })
      .then(setSvg)
      .catch(() => setSvg(null));
  }, [link]);

  return (
    <div className={page.column}>
      <h2 className={page.pageHeading}>Receive money</h2>
      <figure className={styles.qrCard}>
        {svg ? (
          <div
            className={styles.qr}
            role="img"
            aria-label={`QR code that opens a payment to @${handle}`}
            // The SVG is generated locally from our own link; it carries no outside content.
            dangerouslySetInnerHTML={{ __html: svg }}
          />
        ) : (
          <div className={styles.qr} />
        )}
        <figcaption>
          <p className={styles.payMe}>
            Pay <span className="figure">@{handle}</span>
          </p>
          <p className={page.muted}>
            Point a phone camera at this to open a payment to you, already addressed.
          </p>
        </figcaption>
      </figure>
      <div className={formStyles.actions}>
        <button
          type="button"
          className={formStyles.secondary}
          onClick={() =>
            navigator.clipboard.writeText(link).then(
              () => setCopied(true),
              () => setCopied(false),
            )
          }
        >
          {copied ? 'Link copied' : 'Copy my payment link'}
        </button>
        <span className={`${page.muted} figure`} aria-live="polite">
          {copied ? link : ''}
        </span>
      </div>
    </div>
  );
}
