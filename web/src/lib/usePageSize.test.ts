import { describe, expect, it } from 'vitest';
import { entriesPerPage } from './usePageSize';

const REM = 16;

describe('entriesPerPage', () => {
  it.each([
    // window height, ledger entries (one line each, plus b/f and c/f), activity entries (two lines each)
    [768, 7, 4],
    [900, 10, 6],
    [1080, 15, 8],
  ])('fits a %ipx window: %i ledger lines, %i activity entries', (height, ledger, activity) => {
    expect(entriesPerPage(height, REM, 1, 2)).toBe(ledger);
    expect(entriesPerPage(height, REM, 2)).toBe(activity);
  });

  it('still turns pages usefully in a very short window', () => {
    expect(entriesPerPage(400, REM, 1, 2)).toBe(3);
  });

  it('caps a very tall window at 20 entries', () => {
    expect(entriesPerPage(4000, REM, 1, 2)).toBe(20);
  });

  it('scales with the reader’s font size', () => {
    expect(entriesPerPage(900, 20, 1, 2)).toBeLessThan(entriesPerPage(900, REM, 1, 2));
  });
});
