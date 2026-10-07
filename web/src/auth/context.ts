import { createContext, use } from 'react';

export type Status = 'checking' | 'signedIn' | 'signedOut';

/** Why the book was closed, when it was not the user's own choice. */
export type ClosedReason = 'idle' | 'expired' | null;

/** Banking-app behaviour: the book closes itself after this long without a touch or a keypress. */
export const IDLE_LIMIT_MS = 15 * 60 * 1000;

export interface Auth {
  status: Status;
  closedReason: ClosedReason;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<Auth | null>(null);

export function useAuth(): Auth {
  const auth = use(AuthContext);
  if (!auth) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return auth;
}
