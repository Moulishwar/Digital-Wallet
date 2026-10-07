import formStyles from './Form.module.css';
import styles from './PageTurner.module.css';

/**
 * The controls for turning pages. Earlier pages lie to the left, as they do in a bound book;
 * neither button appears where there is no page to turn to.
 */
export function PageTurner({
  onEarlier,
  onLater,
  busy,
}: {
  onEarlier?: () => void;
  onLater?: () => void;
  busy: boolean;
}) {
  if (!onEarlier && !onLater) {
    return null;
  }
  return (
    <nav className={styles.turner} aria-label="Pages">
      {onEarlier ? (
        <button type="button" className={formStyles.secondary} onClick={onEarlier} disabled={busy}>
          ← Earlier
        </button>
      ) : (
        <span />
      )}
      {onLater && (
        <button type="button" className={formStyles.secondary} onClick={onLater} disabled={busy}>
          Later →
        </button>
      )}
    </nav>
  );
}
