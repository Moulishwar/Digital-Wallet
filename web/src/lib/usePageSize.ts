import { useSyncExternalStore } from 'react';
import { FOLDED, useMediaQuery } from './useMediaQuery';

function subscribe(onChange: () => void) {
  window.addEventListener('resize', onChange);
  return () => window.removeEventListener('resize', onChange);
}

const rootFontSize = () => parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;

/**
 * How many entries a page of the book holds, worked out from the window so that a page always
 * fits without the window scrolling — a tall monitor gets a fuller page, a small laptop a shorter
 * one. On a phone the folded page scrolls anyway, so it simply holds a comfortable number.
 *
 * @param linesPerEntry the ruled lines one entry can take: two in Activity (a line and its note),
 *                      one in the Ledger
 * @param reservedLines lines on the page that are not entries, such as b/f and c/f
 */
export function usePageSize(linesPerEntry: number, reservedLines = 0): number {
  const folded = useMediaQuery(FOLDED);
  const height = useSyncExternalStore(subscribe, () => window.innerHeight);

  if (folded) {
    return linesPerEntry === 1 ? 15 : 10;
  }

  return entriesPerPage(height, rootFontSize(), linesPerEntry, reservedLines);
}

/**
 * The arithmetic behind {@link usePageSize}, for an unfolded book: the ruled lines that fit
 * beneath the page's fixed furniture, shared out between entries. Never fewer than 3 entries, so a
 * squat window still turns pages usefully, and never more than 20, so a tall one does not ask
 * the API for an unreasonably long page.
 */
export function entriesPerPage(
  height: number,
  rem: number,
  linesPerEntry: number,
  reservedLines = 0,
): number {
  const line = 2.25 * rem;
  // Everything on a page that is not ruled lines: the desk above and below the book, the page's
  // padding, its heading, the table's own heading row, and the page-turning buttons.
  const chrome = 7.25 * rem + 20.5 * rem;
  const lines = Math.floor((height - chrome) / line) - reservedLines;
  return Math.min(20, Math.max(3, Math.floor(lines / linesPerEntry)));
}
