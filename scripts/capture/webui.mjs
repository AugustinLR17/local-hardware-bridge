// Automated Web UI capture for Local Hardware Bridge.
//
// Drives the dashboard with a headless Chromium and produces:
//   - docs/assets/web-ui.png            (light theme, "Simple" tab)
//   - docs/assets/web-ui-advanced.png   (advanced tab)
//   - docs/assets/web-ui-security.png   (security tab)
//   - docs/assets/web-ui-dark.png       (dark theme)
//   - docs/assets/web-ui.webm           (short scripted walkthrough video)
//
// Usage (inside a Playwright container that shares the server's network namespace):
//   node webui.mjs [baseUrl] [outDir]
// Defaults: baseUrl=http://localhost:12212  outDir=/out
import { chromium } from 'playwright';
import { rename, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const baseUrl = process.argv[2] || 'http://localhost:12212';
const outDir = process.argv[3] || '/out';
const viewport = { width: 1440, height: 900 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport,
  deviceScaleFactor: 2, // crisp, retina-quality screenshots
  recordVideo: { dir: outDir, size: viewport },
});
const page = await context.newPage();

console.log(`> opening ${baseUrl}`);
await page.goto(baseUrl, { waitUntil: 'networkidle', timeout: 30_000 });
// Vue mounts client-side; wait for the tab strip to exist.
await page.getByRole('tab').first().waitFor({ timeout: 15_000 });
await sleep(800);

async function shot(name) {
  const path = join(outDir, name);
  // Viewport-only (not fullPage) so every screenshot is the same fixed size
  // (matches the recorded video dimensions); long pages are clipped, not stretched.
  await page.screenshot({ path, fullPage: false });
  console.log(`  saved ${name}`);
}

// Simple tab (default landing view)
await shot('web-ui.png');

// Walk the tabs
const tabs = await page.getByRole('tab').all();
const tabNames = ['simple', 'advanced', 'security'];
for (let i = 0; i < tabs.length; i++) {
  await tabs[i].click();
  await sleep(900);
  const label = tabNames[i] || `tab-${i}`;
  if (label !== 'simple') await shot(`web-ui-${label}.png`);
}

// Back to simple, then toggle dark theme for one more shot
await tabs[0].click();
await sleep(500);
const themeBtn = page.locator('button', { hasText: /theme|dark|light|🌙|☀/i }).first();
if (await themeBtn.count()) {
  await themeBtn.click();
  await sleep(900);
  await shot('web-ui-dark.png');
}

await sleep(600);
await context.close(); // flushes the video file
await browser.close();

// Playwright names the video with a random hash; rename to a stable filename.
const files = await readdir(outDir);
const webm = files.find((f) => f.endsWith('.webm') && f !== 'web-ui.webm');
if (webm) {
  await rename(join(outDir, webm), join(outDir, 'web-ui.webm'));
  console.log('  saved web-ui.webm');
}
console.log('> done');
