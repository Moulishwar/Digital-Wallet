/**
 * Money arrives from the API as integer paise and is only ever formatted here, never converted to
 * a fractional rupee number. 12345 paise is shown as "123.45" by splitting the integer, so no
 * floating-point value is ever involved.
 */

const grouping = new Intl.NumberFormat('en-IN');

/** The true minus sign, which lines up with the digits in a tabular column; "-" does not. */
export const MINUS = '−';

/** "1,00,000.00": Indian lakh/crore grouping, no sign, no symbol. */
export function formatAmount(minor: number): string {
  const absolute = Math.abs(minor);
  const rupees = Math.trunc(absolute / 100);
  const paise = absolute % 100;
  return `${grouping.format(rupees)}.${paise.toString().padStart(2, '0')}`;
}

/** "₹1,00,000.00", or "−₹50.00" for a negative amount. */
export function formatRupees(minor: number): string {
  return `${minor < 0 ? MINUS : ''}₹${formatAmount(minor)}`;
}

/** "+50.00" / "−50.00", for places where the direction must be read from the figure itself. */
export function formatSigned(minor: number): string {
  return `${minor < 0 ? MINUS : '+'}${formatAmount(minor)}`;
}

/** What a screen reader should hear: "minus 50 rupees 25 paise" rather than "minus 50.25". */
export function spokenRupees(minor: number): string {
  const absolute = Math.abs(minor);
  const rupees = Math.trunc(absolute / 100);
  const paise = absolute % 100;
  const sign = minor < 0 ? 'minus ' : '';
  const paisePart = paise === 0 ? '' : ` ${paise} paise`;
  return `${sign}${grouping.format(rupees)} rupees${paisePart}`;
}
