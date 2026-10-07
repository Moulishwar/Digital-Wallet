import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { setFolded } from './media';

afterEach(() => {
  cleanup();
  setFolded(false);
});
