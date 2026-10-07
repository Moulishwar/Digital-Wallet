import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PageTurner } from './PageTurner';

describe('PageTurner', () => {
  it('is absent when there is only one page', () => {
    const { container } = render(<PageTurner busy={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('offers only "Earlier" on the latest page', async () => {
    const onEarlier = vi.fn();
    render(<PageTurner onEarlier={onEarlier} busy={false} />);

    expect(screen.queryByRole('button', { name: 'Later →' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '← Earlier' }));
    expect(onEarlier).toHaveBeenCalledOnce();
  });

  it('cannot be turned again while a page is still loading', () => {
    render(<PageTurner onEarlier={() => {}} onLater={() => {}} busy />);

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled();
    }
  });
});
