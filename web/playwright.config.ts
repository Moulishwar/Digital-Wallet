import { defineConfig, devices } from '@playwright/test';

// Every run seeds its own people, so a run never depends on what an earlier one left behind and
// can be repeated against the same stack. Set here so the test workers inherit it.
process.env.E2E_RUN ??= Date.now().toString(36);

/**
 * End-to-end tests against the real stack: the built UI behind nginx, the gateway, the three
 * services and their databases, started with `docker compose` (see e2e/README.md).
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : 3,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'iphone', use: { ...devices['iPhone 15'] } },
    { name: 'android', use: { ...devices['Pixel 7'] } },
  ],
});
