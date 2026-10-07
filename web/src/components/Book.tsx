import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import { useMe } from '../api/queries';
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
 */
export function Book({ left, right, label }: { left: ReactNode; right: ReactNode; label: string }) {
  const { data: me } = useMe();
  const ribbons = me?.roles.includes('ROLE_ADMIN') ? [...RIBBONS, ADMIN_RIBBON] : RIBBONS;

  return (
    <div className={styles.desk}>
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

      <main className={styles.spread} aria-label={label}>
        <section className={`${styles.page} ${styles.left}`}>{left}</section>
        <div className={styles.spine} aria-hidden="true" />
        <section className={`${styles.page} ${styles.right}`}>{right}</section>
      </main>
    </div>
  );
}
