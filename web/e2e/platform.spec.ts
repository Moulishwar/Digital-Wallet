import { expect, test } from './support/test';

test('serves the app under a strict Content Security Policy', async ({ request }) => {
  const response = await request.get('/');
  const csp = response.headers()['content-security-policy'];

  expect(csp).toContain("default-src 'self'");
  expect(csp).toContain("script-src 'self'");
  expect(csp).not.toContain('unsafe-eval');
});

test('is installable: a manifest, icons and a service worker', async ({ page, request }) => {
  const manifest = await (await request.get('/manifest.webmanifest')).json();
  expect(manifest).toMatchObject({ name: 'Digital Wallet', display: 'standalone', start_url: '/' });
  for (const icon of manifest.icons) {
    expect((await request.get(icon.src)).ok(), icon.src).toBe(true);
  }

  await page.goto('/');
  const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope);
  expect(new URL(scope).pathname).toBe('/');
});

test('publishes the API documentation', async ({ request }) => {
  for (const service of ['auth', 'wallet', 'transfer']) {
    const spec = await request.get(`/api/docs/specs/${service}`);
    expect(spec.ok(), service).toBe(true);
    expect((await spec.json()).openapi).toMatch(/^3\./);
  }
});
