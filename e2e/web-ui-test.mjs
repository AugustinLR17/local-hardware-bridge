// Web UI functional tests for Local Hardware Bridge.
//
// Runs inside the E2E Docker container against a live bridge instance.
// Verifies that the most critical user interactions work:
//   - Dashboard loads and shows version
//   - Tab navigation (Simple → Advanced → Security)
//   - Save config button triggers PUT /config.json
//   - Test print modal opens with Text/Image/PDF tabs
//   - Restart button sends ?confirm=true
//
// Usage:
//   node e2e/web-ui-test.mjs [baseUrl]
// Default: http://127.0.0.1:57212

import { chromium } from 'playwright';

const baseUrl = process.argv[2] || 'http://127.0.0.1:57212';
const viewport = { width: 1280, height: 900 };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function ok(name) {
    passed++;
    console.log(`OK   ${name}`);
}

function fail(name, msg) {
    failed++;
    console.error(`FAIL ${name}: ${msg}`);
}

async function run() {
    const browser = await chromium.launch();
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();

    // Capture console errors to detect JS crashes
    const consoleErrors = [];
    page.on('console', msg => {
        if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    // ─── 1. Dashboard loads ───
    try {
        await page.goto(baseUrl, { waitUntil: 'networkidle', timeout: 15000 });
        await page.waitForSelector('.lhb-nav-brand-name', { timeout: 10000 });
        const brandText = await page.textContent('.lhb-nav-brand-name');
        if (brandText && brandText.includes('Local Hardware Bridge')) {
            ok('dashboard loads with app name');
        } else {
            fail('dashboard loads with app name', `got "${brandText}"`);
        }
    } catch (e) {
        fail('dashboard loads with app name', e.message);
    }

    // ─── 2. No JS console errors at load ───
    if (consoleErrors.length === 0) {
        ok('no JS console errors at load');
    } else {
        fail('no JS console errors at load', `${consoleErrors.length} errors: ${consoleErrors.join('; ')}`);
    }

    // ─── 3. Version displayed ───
    try {
        const subText = await page.textContent('.lhb-nav-brand-sub');
        if (subText && subText.match(/v\d+\.\d+\.\d+/)) {
            ok('version displayed in nav');
        } else {
            fail('version displayed in nav', `got "${subText}"`);
        }
    } catch (e) {
        fail('version displayed in nav', e.message);
    }

    // ─── 4. Tab navigation ───
    const tabs = ['Simple', 'Advanced', 'Security'];
    for (const tabLabel of tabs) {
        try {
            const tab = page.getByRole('tab', { name: tabLabel });
            if (await tab.count() === 0) {
                // Try clicking by text
                await page.locator(`text=${tabLabel}`).first().click({ timeout: 5000 });
            } else {
                await tab.click({ timeout: 5000 });
            }
            await sleep(300);
            ok(`tab "${tabLabel}" clickable`);
        } catch (e) {
            fail(`tab "${tabLabel}" clickable`, e.message);
        }
    }

    // ─── 5. Save config button ───
    try {
        // Go back to Advanced tab to find the Save button
        await page.locator('text=Advanced').first().click({ timeout: 5000 }).catch(() => {});
        await sleep(300);
        const saveBtn = page.locator('button:has-text("Save")').first();
        await saveBtn.waitFor({ state: 'visible', timeout: 5000 });

        // Intercept the PUT /config.json request
        let saveCalled = false;
        page.on('request', req => {
            if (req.method() === 'PUT' && req.url().includes('/config.json')) {
                saveCalled = true;
            }
        });
        await saveBtn.click({ timeout: 5000 });
        await sleep(2000);
        if (saveCalled) {
            ok('save button triggers PUT /config.json');
        } else {
            fail('save button triggers PUT /config.json', 'no PUT request observed');
        }
    } catch (e) {
        fail('save button triggers PUT /config.json', e.message);
    }

    // ─── 6. Test print modal opens ───
    try {
        // Go to Simple tab
        await page.locator('text=Simple').first().click({ timeout: 5000 });
        await sleep(500);

        // Find the first "Test print" button
        const testBtn = page.locator('button:has-text("Test print")').first();
        await testBtn.waitFor({ state: 'visible', timeout: 5000 });

        // Click it
        await testBtn.click({ timeout: 5000 });
        await sleep(500);

        // Check modal appeared
        const modal = page.locator('.lhb-modal-overlay');
        await modal.waitFor({ state: 'visible', timeout: 3000 });
        const isModalVisible = await modal.isVisible();
        if (isModalVisible) {
            ok('test print modal opens on button click');
        } else {
            fail('test print modal opens on button click', 'modal not visible');
        }

        // ─── 7. Modal has 3 format tabs ───
        const txtTab = page.locator('.lhb-format-tab:has-text("Text")');
        const imageTab = page.locator('.lhb-format-tab:has-text("Image")');
        const pdfTab = page.locator('.lhb-format-tab:has-text("PDF")');
        const txtVisible = await txtTab.isVisible();
        const imageVisible = await imageTab.isVisible();
        const pdfVisible = await pdfTab.isVisible();
        if (txtVisible && imageVisible && pdfVisible) {
            ok('modal has Text/Image/PDF format tabs');
        } else {
            fail('modal has Text/Image/PDF format tabs', `txt=${txtVisible} image=${imageVisible} pdf=${pdfVisible}`);
        }

        // ─── 8. Text tab shows editable textarea ───
        const textarea = page.locator('#test-txt');
        const textareaVisible = await textarea.isVisible();
        if (textareaVisible) {
            ok('text tab shows editable textarea');
        } else {
            fail('text tab shows editable textarea', 'textarea not visible');
        }

        // ─── 9. Switch to Image tab shows canvas ───
        await imageTab.click();
        await sleep(500);
        const canvas = page.locator('#testCanvas');
        const canvasVisible = await canvas.isVisible();
        if (canvasVisible) {
            ok('image tab shows preview canvas');
        } else {
            fail('image tab shows preview canvas', 'canvas not visible');
        }

        // ─── 10. Switch to PDF tab shows canvas ───
        await pdfTab.click();
        await sleep(500);
        const pdfCanvas = page.locator('#testCanvasPdf');
        const pdfCanvasVisible = await pdfCanvas.isVisible();
        if (pdfCanvasVisible) {
            ok('pdf tab shows preview canvas');
        } else {
            fail('pdf tab shows preview canvas', 'canvas not visible');
        }

        // ─── 11. Close modal ───
        const closeBtn = page.locator('.lhb-modal-close');
        await closeBtn.click({ timeout: 3000 });
        await sleep(300);
        const modalStillVisible = await modal.isVisible();
        if (!modalStillVisible) {
            ok('modal closes on X button');
        } else {
            fail('modal closes on X button', 'modal still visible');
        }
    } catch (e) {
        fail('test print modal opens on button click', e.message);
    }

    // ─── 12. Restart button sends ?confirm=true ───
    try {
        // Restart button is in the nav
        const restartBtn = page.locator('button:has-text("Restart")').first();
        await restartBtn.waitFor({ state: 'visible', timeout: 5000 });

        // Set up request listener before clicking
        let restartUrl = null;
        page.on('request', req => {
            if (req.method() === 'POST' && req.url().includes('restart')) {
                restartUrl = req.url();
            }
        });

        // Click restart (may show a confirm dialog if dirty)
        page.on('dialog', dialog => dialog.accept());
        await restartBtn.click({ timeout: 5000 });
        await sleep(2000);

        if (restartUrl && restartUrl.includes('confirm=true')) {
            ok('restart button sends ?confirm=true');
        } else {
            fail('restart button sends ?confirm=true', `got url="${restartUrl}"`);
        }
    } catch (e) {
        fail('restart button sends ?confirm=true', e.message);
    }

    await browser.close();

    console.log(`\n${passed} passed, ${failed} failed`);
    process.exit(failed > 0 ? 1 : 0);
}

run().catch(e => {
    console.error('FATAL:', e);
    process.exit(1);
});
