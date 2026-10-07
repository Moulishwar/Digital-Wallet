import * as Dialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import styles from './Sheet.module.css';

/**
 * A page slid up over the book, for showing one thing on a phone where there is no facing page.
 * Radix supplies the behaviour a dialog must have — focus moved in and trapped, Escape to close,
 * focus returned afterwards, announced as a dialog — and none of the look.
 */
export function Sheet({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.sheet} aria-describedby={undefined}>
          <div className={styles.grip} aria-hidden="true" />
          <Dialog.Title className="visually-hidden">{title}</Dialog.Title>
          {children}
          <Dialog.Close className={styles.close}>Close</Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
