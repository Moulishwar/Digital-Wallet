import { useSyncExternalStore } from 'react';

/** Whether a media query currently matches, kept in step as the window changes. */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    () => window.matchMedia(query).matches,
  );
}

/** The width below which the open book folds into a single page. Mirrors the CSS breakpoint. */
export const FOLDED = '(max-width: 60rem)';
