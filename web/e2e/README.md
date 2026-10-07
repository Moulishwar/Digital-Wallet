# End-to-end tests

Playwright drives the real system: the built UI behind nginx, the gateway, the three services
and their databases. Every run registers its own people through the public API, so runs never
depend on each other and can be repeated against the same stack.

| Project   | Browser  | Device     |
| --------- | -------- | ---------- |
| `desktop` | Chromium | 1440 × 900 |
| `iphone`  | WebKit   | iPhone 15  |
| `android` | Chromium | Pixel 7    |

## Running them

Start the stack with the test overrides layered on (from the repository root):

```bash
docker compose -f docker-compose.yml -f web/e2e/compose.e2e.yml --profile services up -d --build --wait
```

The overrides allow the refresh cookie over plain `http://localhost` (WebKit drops `Secure`
cookies there), make `e2e-auditor@example.com` an auditor, and raise the gateway's per-address
limit on anonymous calls, since the whole suite signs in from one address.

Then, from `web/`:

```bash
npx playwright install --with-deps chromium webkit   # once
npm run e2e                                          # all three projects
npm run e2e -- --project=iphone                      # one of them
npm run e2e:report                                   # the HTML report, with screenshots
```

`E2E_BASE_URL` points the suite at another address; it defaults to `http://localhost:3000`.

Playwright's browsers support Ubuntu and Debian. Elsewhere, run the suite in Playwright's own
image instead:

```bash
docker run --rm --network host --ipc host --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD":/work -w /work mcr.microsoft.com/playwright:v1.63.0-noble npm run e2e
```

## What they cover

- **session**: opening an account, a refused password, staying signed in across a reload with no
  token readable by script, signing out, the 15-minute idle lock, arriving from a payment link.
- **money**: adding test money, paying someone through every step and seeing it arrive on their
  side, the balance limit, the 30-character note, unknown handles, a refused payment.
- **paging**: turning pages back and forth, reloading, the browser's Back button, and each Ledger
  page's balance brought forward matching the page before.
- **layout**: no screen scrolls sideways on any device; on a desktop nothing leaves its page and
  Activity and the Ledger fit the window at 768, 900 and 1080 pixels tall. Measured with a
  60-letter name, a 30-letter note and a 32-character handle, none with a space to break at.
- **accessibility**: axe-core finds no WCAG 2.2 AA violation on any screen, light or dark.
- **admin**: only the auditor sees and can run the ledger health check.
- **platform**: the Content Security Policy, the installable app manifest and service worker,
  and the published API documentation.
