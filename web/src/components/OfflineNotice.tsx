import { useSyncExternalStore } from 'react';
import styles from './OfflineNotice.module.css';

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange);
  window.addEventListener('offline', onChange);
  return () => {
    window.removeEventListener('online', onChange);
    window.removeEventListener('offline', onChange);
  };
}

/**
 * Says so plainly when the network is gone. An installed app opens offline, and nothing on screen
 * should suggest that money can move while it is.
 */
export function OfflineNotice() {
  const online = useSyncExternalStore(subscribe, () => navigator.onLine);
  if (online) {
    return null;
  }
  return (
    <p className={styles.notice} role="status">
      You are offline. Nothing can be sent or added until you are back online.
    </p>
  );
}
