import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { refreshSession, setSignedOutHandler, signIn, signOut } from '../api/client';
import { AuthContext, IDLE_LIMIT_MS, type Auth, type ClosedReason, type Status } from './context';

const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>('checking');
  const [closedReason, setClosedReason] = useState<ClosedReason>(null);
  const lastActivity = useRef(0);

  const ended = useCallback(
    (reason: ClosedReason) => {
      // Nothing from the previous session may linger on screen or in the cache.
      queryClient.clear();
      setClosedReason(reason);
      setStatus('signedOut');
    },
    [queryClient],
  );

  useEffect(() => {
    setSignedOutHandler(() => ended('expired'));
    // A reload has no access token in memory. If the refresh cookie is still good, the session
    // resumes without asking for the password again.
    refreshSession().then((resumed) => setStatus(resumed ? 'signedIn' : 'signedOut'));
  }, [ended]);

  // The idle lock. Checked on a timer and whenever the tab comes back into view, since a timer in
  // a background tab may not fire for a long time.
  useEffect(() => {
    if (status !== 'signedIn') {
      return;
    }
    lastActivity.current = Date.now();
    const touch = () => {
      lastActivity.current = Date.now();
    };
    const check = () => {
      if (Date.now() - lastActivity.current >= IDLE_LIMIT_MS) {
        // Revoked server-side too, so the cookie is useless to anyone who picks up the device.
        signOut().finally(() => ended('idle'));
      }
    };
    ACTIVITY_EVENTS.forEach((event) => window.addEventListener(event, touch, { passive: true }));
    document.addEventListener('visibilitychange', check);
    const timer = window.setInterval(check, 30_000);
    return () => {
      ACTIVITY_EVENTS.forEach((event) => window.removeEventListener(event, touch));
      document.removeEventListener('visibilitychange', check);
      window.clearInterval(timer);
    };
  }, [status, ended]);

  const value = useMemo<Auth>(
    () => ({
      status,
      closedReason,
      signIn: async (email, password) => {
        await signIn(email, password);
        setClosedReason(null);
        setStatus('signedIn');
      },
      signOut: async () => {
        await signOut();
        ended(null);
      },
    }),
    [status, closedReason, ended],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}
