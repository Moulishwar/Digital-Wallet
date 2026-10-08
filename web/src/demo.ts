import { useQuery } from '@tanstack/react-query';

/** One of the shared accounts a visitor can open with a tap. Mirrors web/demo/demo.json. */
export interface DemoAccount {
  role: 'person' | 'auditor';
  handle: string;
  fullName: string;
  email: string;
  password: string;
}

export interface Demo {
  /**
   * When something wipes the demo back to its starting state each day, in words, e.g. "03:30 IST".
   * Set by the web container's DEMO_RESETS_AT; absent when nothing does.
   */
  resetsAt?: string;
  accounts: DemoAccount[];
}

/**
 * Whether this is a demo, and its accounts. The web container serves /demo.json only when
 * started with DEMO_MODE=true; anywhere else the request fails and the app is an ordinary wallet.
 */
export function useDemo(): Demo | null {
  const demo = useQuery({
    queryKey: ['demo'],
    queryFn: async () => {
      const response = await fetch('/demo.json');
      if (!response.ok || !response.headers.get('Content-Type')?.includes('json')) {
        return null;
      }
      return (await response.json()) as Demo;
    },
    staleTime: Infinity,
    retry: false,
  });
  return demo.data ?? null;
}
