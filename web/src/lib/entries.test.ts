import { describe, expect, it } from 'vitest';
import { line } from '../test/lines';
import { lineDate, pageSpan, particulars } from './entries';

describe('particulars', () => {
  it('says "To" for money out and "From" for money in', () => {
    expect(particulars(line({ amountMinor: -5_000 }))).toBe('To Ravi Kumar');
    expect(particulars(line({ amountMinor: 5_000 }))).toBe('From Ravi Kumar');
  });

  it('falls back to the handle, then to plain words, but never to an id', () => {
    expect(particulars(line({ counterpartyName: null }))).toBe('To @ravi');
    const unnamed = { counterpartyName: null, counterpartyHandle: null };
    expect(particulars(line({ ...unnamed, amountMinor: -1 }))).toBe('Payment sent');
    expect(particulars(line({ ...unnamed, amountMinor: 1 }))).toBe('Payment received');
  });

  it('names top-ups and reversals for what they are', () => {
    expect(particulars(line({ type: 'TOPUP', amountMinor: 100_000 }))).toBe('Test money added');
    expect(particulars(line({ type: 'REVERSAL' }))).toBe('Reversal');
  });
});

describe('lineDate', () => {
  it('dates a line by day and month', () => {
    expect(lineDate('2026-10-07T06:30:00Z')).toBe('07 Oct');
  });
});

describe('pageSpan', () => {
  it('is empty for an empty page', () => {
    expect(pageSpan([])).toBe('');
  });

  it('gives one date when the whole page is one day', () => {
    expect(pageSpan([line(), line({ occurredAt: '2026-10-07T08:00:00Z' })])).toBe('7 Oct 2026');
  });

  it('runs from the oldest line to the newest, whatever order the lines come in', () => {
    const lines = [
      line({ occurredAt: '2026-10-07T06:30:00Z' }),
      line({ occurredAt: '2026-09-28T06:30:00Z' }),
    ];
    expect(pageSpan(lines)).toMatch(/^28 Sept? 2026 – 7 Oct 2026$/);
  });
});
