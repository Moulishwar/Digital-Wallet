import { useLocation, useNavigate, useSearchParams } from 'react-router';

interface Trail {
  /** The cursors of the pages turned through to get here, oldest turn first. */
  trail: (string | null)[];
}

/**
 * Turning through the book a page at a time.
 *
 * The page on show is in the address (`?from=<cursor>`), so a reload or a shared link opens the
 * same page. The way back is the list of earlier cursors, kept in the browser's history state:
 * the statement API only pages forward, and this is what lets "Later" return to where the reader
 * came from. Opening a link without that history simply returns to the latest page.
 */
export function usePageTurns() {
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const cursor = params.get('from');
  const trail = (location.state as Trail | null)?.trail ?? [];

  const go = (to: string | null, nextTrail: (string | null)[]) => {
    const next = new URLSearchParams(params);
    if (to) {
      next.set('from', to);
    } else {
      next.delete('from');
    }
    // An entry open on the facing page belongs to the page being left.
    next.delete('entry');
    navigate({ search: next.toString() }, { state: { trail: nextTrail } satisfies Trail });
  };

  return {
    cursor,
    /** Turn to older entries. */
    earlier: (nextCursor: string) => go(nextCursor, [...trail, cursor]),
    /** Turn back toward the newest entries; absent on the latest page. */
    later: cursor === null ? undefined : () => go(trail.at(-1) ?? null, trail.slice(0, -1)),
    /** Keeps the trail when something else on the page changes the address, such as opening an entry. */
    state: location.state as Trail | null,
  };
}
