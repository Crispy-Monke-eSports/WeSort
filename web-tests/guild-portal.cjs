// Run through docker-smoke.py --browser. Manager and its HTTP sessions are real.
// Covers the React/MUI dashboard: two members log in, search shared stock,
// add to the draft, walk cart -> details -> review -> place, and see private
// order histories (member isolation); an officer audits by person and item.
// Any page error or request leaving the Manager origin fails the run.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');

async function main() {
  const { url, stash, otherStash, password, members, officer } = JSON.parse(fs.readFileSync(0, 'utf8'));
  void stash;
  void otherStash;
  const browser = await chromium.launch({ executablePath: process.env.WESORT_CHROMIUM || undefined });
  const errors = [];
  const external = [];
  async function login(username, viewport) {
    const context = await browser.newContext({ viewport });
    await context.route('**/*', route => {
      if (new URL(route.request().url()).origin === url) return route.continue();
      external.push(new URL(route.request().url()).origin);
      return route.abort();
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url);
    await page.locator('#auth-username').fill(username);
    await page.locator('#auth-password').fill(password);
    await page.locator('#auth-submit').click();
    await page.waitForURL(/\/overview/, { timeout: 20000 });
    return page;
  }
  async function inventorySearch(page, text) {
    const openNavigation = page.getByRole('button', { name: 'Open navigation' });
    if (await openNavigation.isVisible()) await openNavigation.click();
    await page.locator('.MuiDrawer-paper').getByRole('link', { name: 'Inventory', exact: true }).click();
    await expectHeading(page, 'Inventory');
    await page.getByLabel('Search items').first().fill(text);
  }
  async function expectHeading(page, name) {
    await page.getByRole('heading', { name }).first().waitFor({ timeout: 10000 });
  }
  try {
    for (const [width, height] of [[390, 844], [768, 1024], [1440, 900], [1920, 1080]]) {
      const page = await login(members[0], { width, height });
      try {
        await inventorySearch(page, 'diamond');
        await page.waitForFunction(() => {
          const icon = document.querySelector('[data-testid="item-grid"] img[src^="/icon/minecraft/diamond.png"]');
          return icon?.complete && icon.naturalWidth > 0;
        });
        assert.ok(
          await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
          `Inventory overflows the ${width}×${height} viewport`,
        );
        await page.getByTestId('item-grid').locator('.MuiCard-root').first()
          .getByRole('button', { name: 'Add' }).click();
        await page.getByRole('spinbutton', { name: 'Quantity (items)', exact: true }).waitFor();
      } finally {
        await page.context().close();
      }
    }

    const pages = [];
    for (const username of members) {
      const page = await login(username);
      await inventorySearch(page, 'diamond');
      await page.getByTestId('item-grid').locator('.MuiCard-root').first().waitFor();
      pages.push(page);
    }

    // Each member drafts 64 diamonds with a distinct comment and places.
    const notes = ['Portal building order', 'Portal mining order'];
    for (const [i, page] of pages.entries()) {
      await page.getByTestId('item-grid').locator('.MuiCard-root').first().getByRole('button', { name: 'Add' }).click();
      await page.getByRole('spinbutton', { name: 'Quantity (items)', exact: true }).fill('64');
      await page.getByRole('button', { name: 'Add to cart' }).click();
      await page.getByRole('button', { name: /cart/i }).click();
      await expectCartLines(page, 1);
      await page.getByRole('button', { name: 'Checkout', exact: true }).click();
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByLabel('Comment (optional)').fill(notes[i]);
      await page.getByRole('button', { name: 'Review' }).click();
      await expect(page.locator('#order-review')).toContainText(notes[i]);
      await page.getByRole('button', { name: 'Place orders' }).click();
      await page.getByText(/Confirmed o-/).first().waitFor({ timeout: 15000 });
      await page.getByRole('button', { name: 'Done' }).click();
    }

    for (const [i, page] of pages.entries()) {
      await page.reload();
      // Private history: exactly one order (the member's own), with its note.
      await page.getByRole('navigation').getByRole('link', { name: 'Orders', exact: true }).click();
      await expectHeading(page, 'Orders');
      const history = page.getByTestId('order-card');
      await expectCount(history, 1);
      await expect(history.first()).toContainText(notes[i]);
      await expect(page.getByText(notes[1 - i], { exact: false })).toHaveCount(0);
      // No audit surface for members (backend ORDERS_ALL, UI-gated too).
      // Members lack the activity perm so Operations is not in their nav;
      // reach it directly and verify no audit tab or operator queues render.
      await page.goto(url + '/operations');
      await expectHeading(page, 'Operations');
      await expect(page.getByRole('tab', { name: 'Order audit' })).toHaveCount(0);
      await expect(page.getByText('Operator queues')).toHaveCount(0);
      await inventorySearch(page, 'diamond');
      await page.getByTestId('item-grid').locator('.MuiCard-root').first().waitFor();
    }

    // Officer: same login, then audit by person + item finds the one order.
    const reviewer = await login(officer);
    await reviewer.getByRole('navigation').getByRole('link', { name: 'Operations', exact: true }).click();
    await expectHeading(reviewer, 'Operations');
    await reviewer.getByRole('tab', { name: 'Order audit' }).click();
    const audit = reviewer.locator('#order-audit');
    await audit.getByLabel('Person').fill(members[0]);
    await audit.getByLabel('Item').fill('diamond');
    await audit.getByRole('button', { name: 'Search', exact: true }).click();
    const rows = audit.locator('tbody tr');
    await expectCount(rows, 1);
    await expect(rows.first()).toContainText(members[0]);
    await expect(rows.first()).toContainText('minecraft:diamond');
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
  } finally {
    await browser.close();
  }
}

async function expectCartLines(page, n) {
  await page.locator('#cart-lines .cart-line').first().waitFor({ timeout: 10000 });
  assert.equal(await page.locator('#cart-lines .cart-line').count(), n);
}

async function expectCount(locator, n) {
  if (n === 0) {
    assert.equal(await locator.count(), 0);
    return;
  }
  await locator.first().waitFor({ timeout: 15000 });
  assert.equal(await locator.count(), n);
}

function expect(locator) {
  return {
    toContainText: (t) => locator.filter({ hasText: t }).first().waitFor({ timeout: 10000 }),
    toHaveCount: (n) => expectCount(locator, n),
  };
}

main().catch(error => { console.error(error); process.exitCode = 1; });
