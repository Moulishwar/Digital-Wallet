import { describe, expect, it } from 'vitest';
import { amountInWords, parseRupees } from './amounts';

describe('parseRupees', () => {
  it('reads rupees and paise without floating-point error', () => {
    // 4.35 * 100 is 434.99999999999994 in floating point.
    expect(parseRupees('4.35')).toBe(435);
    expect(parseRupees('0.1')).toBe(10);
    expect(parseRupees('999999999.99')).toBe(99_999_999_999);
  });

  it('accepts the commas and spaces people type', () => {
    expect(parseRupees('1,250.5')).toBe(125_050);
    expect(parseRupees(' 100 ')).toBe(10_000);
    expect(parseRupees('7.')).toBe(700);
  });

  it.each(['', 'abc', '-5', '.5', '1.234', '1e3', '12.3.4', '1234567890'])('rejects %j', (text) => {
    expect(parseRupees(text)).toBeNull();
  });
});

describe('amountInWords', () => {
  it('writes the amount as on a cheque', () => {
    expect(amountInWords(45_050)).toBe('Rupees Four Hundred Fifty and Paise Fifty Only');
    expect(amountInWords(100)).toBe('Rupees One Only');
    expect(amountInWords(0)).toBe('Rupees Zero Only');
  });

  it('counts in lakhs and crores', () => {
    expect(amountInWords(123_456_789)).toBe(
      'Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Paise Eighty Nine Only',
    );
    expect(amountInWords(10_100_000)).toBe('Rupees One Lakh One Thousand Only');
    expect(amountInWords(1_000_000_000)).toBe('Rupees One Crore Only');
    expect(amountInWords(100_000_000_000)).toBe('Rupees One Hundred Crore Only');
  });

  it('ignores the sign: a cheque is never negative', () => {
    expect(amountInWords(-1_500)).toBe(amountInWords(1_500));
  });
});
