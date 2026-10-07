import type { InputHTMLAttributes, ReactNode } from 'react';
import styles from './Form.module.css';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'prefix'> {
  label: string;
  name: string;
  hint?: ReactNode;
  prefix?: string;
  /** Larger figures, for the amount being written. */
  large?: boolean;
}

/** A labelled line to write on. Inputs are lines, never boxes. */
export function Field({ label, name, hint, prefix, large, className, ...input }: FieldProps) {
  const hintId = hint ? `${name}-hint` : undefined;
  return (
    <label className={`${styles.field} ${className ?? ''}`}>
      <span className={styles.label}>{label}</span>
      <span className={`${styles.line} ${large ? styles.large : ''}`}>
        {prefix && (
          <span className={styles.prefix} aria-hidden="true">
            {prefix}
          </span>
        )}
        <input name={name} aria-describedby={hintId} {...input} />
      </span>
      {hint && (
        <span id={hintId} className={styles.hint}>
          {hint}
        </span>
      )}
    </label>
  );
}

export function FormError({ children }: { children: ReactNode }) {
  return (
    <p className={styles.error} role="alert">
      {children}
    </p>
  );
}
