/**
 * jsdom has no layout, so it has no matchMedia either. This stands in for it, letting a test
 * choose whether the book is open (a wide window) or folded onto a phone.
 */
let folded = false;

export function setFolded(value: boolean): void {
  folded = value;
}

window.matchMedia = (query: string) =>
  ({
    matches: query.includes('max-width') ? folded : false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;
