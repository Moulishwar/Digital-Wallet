/**
 * Turning what someone typed into paise, and paise into words.
 *
 * Parsing works on the string: "450.5" becomes 45050 by joining digits, never by multiplying a
 * floating-point number by 100 (which turns 4.35 into 434.99999…).
 */

const AMOUNT = /^(\d{1,9})(?:\.(\d{0,2}))?$/;

/** "1,250.5" → 125050. Null when the text is not a well-formed rupee amount. */
export function parseRupees(text: string): number | null {
  const match = AMOUNT.exec(text.trim().replaceAll(',', ''));
  if (!match) {
    return null;
  }
  const rupees = match[1];
  const paise = (match[2] ?? '').padEnd(2, '0');
  return Number(rupees) * 100 + Number(paise);
}

const ONES = [
  '',
  'One',
  'Two',
  'Three',
  'Four',
  'Five',
  'Six',
  'Seven',
  'Eight',
  'Nine',
  'Ten',
  'Eleven',
  'Twelve',
  'Thirteen',
  'Fourteen',
  'Fifteen',
  'Sixteen',
  'Seventeen',
  'Eighteen',
  'Nineteen',
];
const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];

function belowHundred(n: number): string {
  if (n < 20) {
    return ONES[n];
  }
  return `${TENS[Math.trunc(n / 10)]}${n % 10 ? ` ${ONES[n % 10]}` : ''}`;
}

function belowThousand(n: number): string {
  const hundreds = Math.trunc(n / 100);
  const rest = n % 100;
  return [hundreds ? `${ONES[hundreds]} Hundred` : '', rest ? belowHundred(rest) : '']
    .filter(Boolean)
    .join(' ');
}

/** 1234567 → "Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven", in the Indian system. */
function indianWords(n: number): string {
  if (n === 0) {
    return 'Zero';
  }
  const crore = Math.trunc(n / 10_000_000);
  const lakh = Math.trunc((n % 10_000_000) / 100_000);
  const thousand = Math.trunc((n % 100_000) / 1000);
  const rest = n % 1000;
  return [
    crore ? `${indianWords(crore)} Crore` : '',
    lakh ? `${belowHundred(lakh)} Lakh` : '',
    thousand ? `${belowHundred(thousand)} Thousand` : '',
    rest ? belowThousand(rest) : '',
  ]
    .filter(Boolean)
    .join(' ');
}

/** 45050 → "Rupees Four Hundred Fifty and Paise Fifty Only" — written as on a cheque. */
export function amountInWords(minor: number): string {
  const rupees = Math.trunc(Math.abs(minor) / 100);
  const paise = Math.abs(minor) % 100;
  const paisePart = paise ? ` and Paise ${belowHundred(paise)}` : '';
  return `Rupees ${indianWords(rupees)}${paisePart} Only`;
}
