// Run through docker-smoke.py --browser. Manager and its HTTP sessions are real.
// Covers the unified Shop (PR #89, React): members log in, search the stash,
// shift-click a stack into the cart, walk cart -> delivery -> review -> place,
// and see private order histories; an officer audits by person and item.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');

async function main() {
  const { url, stash, otherStash, password, members, officer } = JSON.parse(fs.readFileSync(0, 'utf8'));
  const browser = await chromium.launch({ executablePath: process.env.WESORT_CHROMIUM || undefined });
  const errors = [];
  const external = [];
  async function login(username) {
    const context = await browser.newContext();
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
    await page.waitForURL(/\/shop/, { timeout: 15000 });
    return page;
  }
  try {
    const pages = [];
    for (const username of members) {
      const page = await login(username);
      const search = page.getByRole('searchbox', { name: 'Search the stash' });
      await search.fill('diamond @minecraft');
      await page.locator('#item-grid .slot').first().waitFor();
      await search.fill('=minecraft:diamond');
      pages.push(page);
    }

    const notes = ['Portal building order', 'Portal mining order'];
    for (const [i, page] of pages.entries()) {
      await page.locator('#stash-select').selectOption({ label: new RegExp(`${stash}$`) }).catch(() => {});
      const search = page.getByRole('searchbox', { name: 'Search the stash' });
      assert.equal(await search.inputValue(), '=minecraft:diamond');
      await page.locator('#item-grid .slot').first().click({ modifiers: ['Shift'] });
      await page.locator('#dash-nav [data-page="shop"]').click();
      assert.match(await page.locator('#cart-totals').textContent(), /64 items/);
      await page.getByRole('button', { name: 'Continue to delivery' }).click();
      await page.locator('#order-comment').fill(notes[i]);
      await page.getByRole('button', { name: 'Continue to review' }).click();
      await page.locator('#cart-place').click();
      await page.locator('#cart-status').getByText(/Order placed/).waitFor();
      const history = page.locator('#order-list');
      await history.getByText(notes[i], { exact: false }).waitFor();
      assert.equal(await page.locator('#order-history-title').textContent(), 'Your orders');
      assert.equal(await history.locator('.order-card').count(), 1);
    }

    for (const [i, page] of pages.entries()) {
      await page.reload();
      await page.locator('#dash-nav [data-page="orders"]').click();
      const history = page.locator('#order-list');
      await history.getByText(notes[i], { exact: false }).waitFor();
      assert.equal(await history.locator('.order-card').count(), 1);
      assert.equal(await history.getByText(notes[1 - i], { exact: false }).count(), 0);
      assert.equal(await page.locator('#order-audit').count(), 0);
      await page.locator('#dash-nav [data-page="shop"]').click();
      await page.getByRole('searchbox', { name: 'Search the stash' }).fill('=minecraft:diamond');
      await page.locator('#item-grid .slot').first().waitFor();
    }

    const reviewer = await login(officer);
    await reviewer.locator('#dash-nav [data-page="orders"]').click();
    await reviewer.getByText('Order audit', { exact: true }).scrollIntoViewIfNeeded();
    const audit = reviewer.locator('#order-audit');
    await audit.getByRole('button', { name: 'Search audit' }).waitFor();
    await audit.getByRole('searchbox', { name: 'Person' }).fill(members[0]);
    await audit.getByRole('searchbox', { name: 'Item key' }).fill('diamond');
    await audit.getByRole('button', { name: 'Search audit' }).click();
    const result = audit.locator('#order-audit-results details');
    await result.getByText(members[0], { exact: false }).first().waitFor();
    assert.equal(await result.count(), 1);
    assert.match(await result.textContent(), /minecraft:diamond.*order.reserve/);
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
