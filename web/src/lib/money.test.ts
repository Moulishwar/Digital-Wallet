import { describe, expect, it } from 'vitest';
import { formatAmount, formatRupees, formatSigned, MINUS, spokenRupees } from './money';

describe('formatAmount', () => {
  it('groups rupees the Indian way, in lakhs and crores', () => {
    expect(formatAmount(10_000_000)).toBe('1,00,000.00');
    expect(formatAmount(123_456_789_012)).toBe('1,23,45,67,890.12');
  });

  it('always shows two digits of paise', () => {
    expect(formatAmount(0)).toBe('0.00');
    expect(formatAmount(5)).toBe('0.05');
    expect(formatAmount(4_350)).toBe('43.50');
  });

  it('drops the sign, leaving direction to the column or the caller', () => {
    expect(formatAmount(-4_505)).toBe('45.05');
  });
});

describe('formatRupees', () => {
  it('puts the rupee symbol after a true minus sign', () => {
    expect(formatRupees(1_000_000)).toBe('₹10,000.00');
    expect(formatRupees(-5_000)).toBe(`${MINUS}₹50.00`);
  });
});

describe('formatSigned', () => {
  it('reads the direction from the figure itself', () => {
    expect(formatSigned(5_000)).toBe('+50.00');
    expect(formatSigned(-5_000)).toBe(`${MINUS}50.00`);
  });

  it('uses U+2212, which lines up with digits, not a hyphen', () => {
    expect(formatSigned(-1)).not.toContain('-');
  });
});

describe('spokenRupees', () => {
  it('says rupees and paise rather than a decimal point', () => {
    expect(spokenRupees(-5_025)).toBe('minus 50 rupees 25 paise');
    expect(spokenRupees(10_000_000)).toBe('1,00,000 rupees');
  });
});
