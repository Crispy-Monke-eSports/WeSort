// Run through docker-smoke.py --browser. Manager and its HTTP sessions are real.
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
    await page.locator('#auth-screen').waitFor({ state: 'hidden' });
    return page;
  }
  try {
    const pages = [];
    for (const username of members) {
      const page = await login(username);
      const stockSearch = page.getByRole('searchbox', { name: 'Search guild stock' });
      await stockSearch.fill('diamond @minecraft');
      await page.locator('#stock-balances').getByText('Diamond', { exact: true }).waitFor();
      assert.equal(await page.locator('#stock-balances').getByText('Oak Planks', { exact: true }).count(), 0);
      assert.equal(await page.locator('#stock-source-details').isVisible(), false);
      await stockSearch.fill('=minecraft:diamond');
      pages.push(page);
    }

    const readBalance = async page => (await page.locator('#stock-balances td').allTextContents()).slice(2).map(Number);
    const initial = await readBalance(pages[0]);
    assert.deepEqual(await readBalance(pages[1]), initial);
    const [observed, reserved, available, unknown] = initial;
    assert.equal(observed, 2 * (4 * 27 * 64 + 500));
    assert.equal(reserved, 1);
    assert.equal(unknown, 0);
    assert.equal(available, observed - reserved);

    const notes = ['Portal building order', 'Portal mining order'];
    for (const [i, page] of pages.entries()) {
      await page.getByText('Stock per stash', { exact: true }).click();
      await page.locator('#stock-stashes').getByRole('button', { name: otherStash }).click();
      await page.locator('#stock-stashes').getByRole('button', { name: stash }).click();
      assert.equal(JSON.parse(await page.locator('#stash-select').inputValue())[1], stash);
      const search = page.getByRole('searchbox', { name: 'Search selected stash' });
      assert.equal(await search.inputValue(), '=minecraft:diamond');
      await page.locator('#item-grid .slot').click({ modifiers: ['Shift'] });
      await page.locator('#dash-nav [data-page="orders"]').click();
      assert.match(await page.locator('#cart-totals').textContent(), /64 items/);
      await page.locator('#order-comment').fill(notes[i]);
      await page.locator('#cart-place').click();
      await page.locator('#cart-status').getByText(/Order placed/).waitFor();
      const history = page.locator('#order-list');
      await history.getByText(notes[i], { exact: false }).waitFor();
      assert.equal(await page.locator('#order-history-title').textContent(), 'Your orders');
      assert.equal(await history.locator('.order-card').count(), 1);
      assert.match(await history.textContent(), /Reserved/);
    }

    for (const [i, page] of pages.entries()) {
      await page.reload();
      await page.locator('#dash-nav [data-page="orders"]').click();
      const history = page.locator('#order-list');
      await history.getByText(notes[i], { exact: false }).waitFor();
      assert.equal(await history.locator('.order-card').count(), 1);
      assert.equal(await history.getByText(notes[1 - i], { exact: false }).count(), 0);
      assert.equal(await page.locator('#order-audit').isVisible(), false);
      await page.locator('#dash-nav [data-page="stash"]').click();
      await page.getByRole('searchbox', { name: 'Search guild stock' }).fill('=minecraft:diamond');
      await page.locator('#stock-balances tr').waitFor();
      assert.deepEqual(await readBalance(page), [observed, reserved + 128, available - 128, unknown]);
    }

    const reviewer = await login(officer);
    await reviewer.locator('#dash-nav [data-page="orders"]').click();
    await reviewer.getByText('Order audit', { exact: true }).click();
    const audit = reviewer.locator('#order-audit');
    await audit.getByRole('button', { name: 'Search audit' }).waitFor();
    await audit.getByRole('searchbox', { name: 'Person' }).fill(members[0]);
    await audit.getByRole('searchbox', { name: 'Item key' }).fill('diamond');
    await audit.getByRole('button', { name: 'Search audit' }).click();
    const result = audit.locator('#order-audit-results details');
    await result.getByText(members[0], { exact: false }).click();
    assert.equal(await result.count(), 1);
    assert.match(await result.textContent(), /64 × minecraft:diamond.*order.reserve/);
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
