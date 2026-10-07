import styles from './Stamp.module.css';

const stampDate = new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });

/**
 * The app's one deliberate motion: a rubber stamp pressed onto a voucher when money has moved.
 * Under reduced motion it is simply there.
 */
export function Stamp({ label, at }: { label: string; at: Date }) {
  return (
    <div
      className={styles.stamp}
      role="img"
      aria-label={`Stamped ${label.toLowerCase()} on ${stampDate.format(at)}`}
    >
      <span className={styles.word}>{label}</span>
      <span className={`${styles.date} figure`}>{stampDate.format(at)}</span>
    </div>
  );
}
