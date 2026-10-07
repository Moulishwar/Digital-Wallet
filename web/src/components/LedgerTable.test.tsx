import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { MINUS } from '../lib/money';
import { line } from '../test/lines';
import { setFolded } from '../test/media';
import { LedgerTable } from './LedgerTable';

const headers = () => screen.getAllByRole('columnheader').map((h) => h.textContent);

describe('LedgerTable', () => {
  const lines = [
    line({ amountMinor: 20_000, balanceAfterMinor: 120_000, memo: 'Chai and samosas' }),
    line({ amountMinor: -5_000, balanceAfterMinor: 115_000 }),
  ];

  it('gives money in and money out a column each on an open book', () => {
    render(<LedgerTable caption="Recent" lines={lines} />);

    expect(headers()).toEqual(['Date', 'Particulars', 'Money in', 'Money out']);
    const [, first, second] = screen.getAllByRole('row');
    expect(
      within(first)
        .getAllByRole('cell')
        .map((c) => c.textContent),
    ).toEqual(['07 Oct', 'From Ravi KumarChai and samosas', '200.00', '']);
    expect(within(second).getAllByRole('cell').at(-1)).toHaveTextContent('50.00');
  });

  it('folds to one signed column on a phone', () => {
    setFolded(true);
    render(<LedgerTable caption="Recent" lines={lines} />);

    expect(headers()).toEqual(['Date', 'Particulars', 'Amount']);
    expect(screen.getByText(`${MINUS}50.00`)).toBeInTheDocument();
  });

  it('writes Dr, Cr and the running balance in the accountant’s view, without notes', () => {
    render(<LedgerTable caption="Ledger" variant="book" lines={lines} />);

    expect(headers()).toEqual(['Date', 'Particulars', 'Dr', 'Cr', 'Balance']);
    expect(screen.getByText('1,150.00')).toBeInTheDocument();
    expect(screen.queryByText('Chai and samosas')).not.toBeInTheDocument();
  });

  it('opens with the balance brought forward and closes with the balance carried forward', () => {
    render(
      <LedgerTable
        caption="Ledger"
        variant="book"
        lines={lines}
        broughtForwardMinor={100_000}
        carriedForwardMinor={115_000}
      />,
    );

    const rows = screen.getAllByRole('row');
    expect(rows[1]).toHaveTextContent(/Balance b\/f.*₹1,000\.00/);
    expect(rows.at(-1)).toHaveTextContent(/Balance c\/f.*₹1,150\.00/);
  });

  it('shows the empty message instead of an empty table', () => {
    render(<LedgerTable caption="Ledger" lines={[]} broughtForwardMinor={0} empty="Nothing written yet." />);

    expect(screen.getByText('Nothing written yet.')).toBeInTheDocument();
    expect(screen.queryByText(/b\/f/)).not.toBeInTheDocument();
  });

  it('opens a line’s journal entry and marks which one is open', async () => {
    const onOpen = vi.fn();
    render(
      <LedgerTable caption="Activity" lines={lines} onOpen={onOpen} openEntryId={lines[1].journalEntryId} />,
    );

    const [first, second] = screen.getAllByRole('button');
    expect(first).toHaveAttribute('aria-pressed', 'false');
    expect(second).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(first);
    expect(onOpen).toHaveBeenCalledWith(lines[0].journalEntryId);
  });
});
