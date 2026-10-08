import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import { useMe } from '../api/queries';
import { useDemo } from '../demo';
import styles from './Book.module.css';

interface Ribbon {
  to: string;
  label: string;
}

const RIBBONS: Ribbon[] = [
  { to: '/', label: 'Home' },
  { to: '/activity', label: 'Activity' },
  { to: '/ledger', label: 'Ledger' },
  { to: '/profile', label: 'Profile' },
];

const ADMIN_RIBBON: Ribbon = { to: '/admin', label: 'Health' };

/**
 * The open book every signed-in screen lives in: two pages either side of a cloth spine, with the
 * sections as cloth ribbons hanging from the top edge. The current section's ribbon hangs longer.
 *
 * A paged book (Activity, the Ledger) is turned rather than scrolled, so on a wide screen it is
 * exactly the height of the window: should a page ever hold more than fits, that page scrolls
 * within itself and the window never does.
 */
export function Book({
  left,
  right,
  label,
  paged = false,
}: {
  left: ReactNode;
  right: ReactNode;
  label: string;
  paged?: boolean;
}) {
  const { data: me } = useMe();
  const demo = useDemo();
  const ribbons = me?.roles.includes('ROLE_ADMIN') ? [...RIBBONS, ADMIN_RIBBON] : RIBBONS;

  return (
    <div className={styles.desk}>
      {demo && (
        <p className={styles.demoTag}>
          <strong>Demo</strong> · test money{demo.resetsAt && ` · resets at ${demo.resetsAt}`}
        </p>
      )}
      <nav className={styles.ribbons} aria-label="Sections">
        {ribbons.map((ribbon) => (
          <NavLink
            key={ribbon.to}
            to={ribbon.to}
            end
            className={({ isActive }) => (isActive ? `${styles.ribbon} ${styles.current}` : styles.ribbon)}
          >
            {ribbon.label}
          </NavLink>
        ))}
      </nav>

      <main className={paged ? `${styles.spread} ${styles.paged}` : styles.spread} aria-label={label}>
        <section className={`${styles.page} ${styles.left}`}>{left}</section>
        <div className={styles.spine} aria-hidden="true" />
        <section className={`${styles.page} ${styles.right}`}>{right}</section>
      </main>
    </div>
  );
}
