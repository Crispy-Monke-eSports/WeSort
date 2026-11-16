// Executes the actual Docker-served web app against its real API. Cookie stays in process memory.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const base = process.argv[2];
const report = process.argv[3];
const cookie = process.env.WESORT_TEST_COOKIE;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const request = async (path, options = {}) => {
    const url = new URL(path, base);
    assert.equal(url.origin, new URL(base).origin);
    const headers = new Headers(options.headers || {});
    headers.set('Cookie', cookie);
    return fetch(url, {...options, headers});
  };
  const html = await (await request('/')).text();
  const dom = new JSDOM(html, {url: base, runScripts: 'outside-only'});
  const w = dom.window;
  const errors = [];
  try {
    w.addEventListener('error', event => errors.push(String(event.error || event.message)));
    w.fetch = request;
    w.requestAnimationFrame = callback => w.setTimeout(callback, 0);
    w.matchMedia = () => ({matches: false, addEventListener() {}});
    w.HTMLElement.prototype.scrollIntoView = () => {};
    for (const name of ['theme-init.js', 'streamer-init.js', 'app.js']) w.eval(await (await request('/' + name)).text());
    for (let i = 0; i < 200 && w.document.querySelectorAll('#item-grid .slot').length < 10; i++) await pause(100);
    assert.equal(w.document.querySelector('#auth-screen').hidden, true);
    const grid = w.document.querySelector('#item-grid');
    const slots = [...grid.querySelectorAll('.slot')];
    assert.ok(slots.length >= 13, 'Fresh item variants and filled shulker groups were not rendered');
    const names = slots.map(slot => slot.querySelector('.slot-add')?.getAttribute('aria-label') || '');
    assert.ok(names.some(name => name.includes('E2E box')));
    assert.equal(names.filter(name => name.includes('E2E sword')).length, 2, 'Custom-data sword variant merged in web UI');
    assert.ok(grid.querySelectorAll('.dura').length >= 3, 'Damage metadata missing from real UI');
    const tooltips = [];
    for (const slot of slots) {
      slot.dispatchEvent(new w.MouseEvent('mouseover', {bubbles: true, clientX: 50, clientY: 50}));
      tooltips.push(w.document.querySelector('#tooltip').textContent);
    }
    assert.ok(tooltips.some(text => /Unbreaking I/.test(text)), 'Enchantment detail missing');
    const shulkers = w.document.querySelector('[data-tab="shulkers"]');
    assert.ok(shulkers, 'Shulker category missing');
    shulkers.click(); await pause(100);
    const boxes = [...grid.querySelectorAll('.slot')];
    assert.equal(boxes.length, 3, 'Filled shulker content groups merged');
    assert.ok(boxes.every(slot => slot.querySelectorAll('img').length >= 2), 'Filled shulker contents overlay missing');
    assert.deepEqual(errors, []);
    fs.writeFileSync(report, JSON.stringify({result: 'PASS', scope: 'Actual Docker-served web JavaScript and API in JSDOM; no browser pixel rendering claim',
      visibleSlots: slots.length, filledShulkerGroups: boxes.length, names, tooltips}, null, 2) + '\n');
    console.log('PASS: real Manager web DOM shows fresh 26.3 variants, damage, enchantments and distinct filled shulkers');
  } finally { w.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
