const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const root = path.join(__dirname, '../stash-manager-server/src/main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const code = fs.readFileSync(path.join(root, 'app.js'), 'utf8');

const settle = () => new Promise(resolve => setTimeout(resolve, 30));
const rest = async (n = 6) => { for (let i = 0; i < n; i++) await settle(); };

// One box of 27 stacks of 64 diamonds, plus two more of the same kind.
const boxContents = [];
for (let slot = 0; slot < 27; slot++) boxContents.push({slot, key: 'minecraft:diamond', count: 64});

async function scenario() {
  const dom = new JSDOM(html, {url: 'http://localhost:8765', runScripts: 'outside-only'});
  const w = dom.window;
  const errors = [];
  const requests = [];
  const placed = [];
  w.addEventListener('error', e => errors.push(e.error || e.message));
  w.requestAnimationFrame = () => 0;
  w.matchMedia = () => ({matches: false, addEventListener() {}});
  w.HTMLElement.prototype.scrollIntoView = () => {};
  w.fetch = async (url, opts = {}) => {
    requests.push((opts.method || 'GET') + ' ' + url);
    const u = new URL(url, w.location.href);
    let body = {};
    if (u.pathname === '/api/v1/auth/state') body = {loggedIn: true, minLength: 14,
      elevatedUntil: Date.now() + 600000, user: {id: 'owner', name: 'Owner', owner: true}};
    if (u.pathname === '/api/v1/guild') body = {name: 'T', description: '', revision: 1};
    if (u.pathname === '/api/v1/stashes') body = [{server: 'test', stash: 'fixture', online: false,
      indexedAt: Date.now(), player: 'Tester'}];
    if (u.pathname === '/api/v1/index') body = {mcVersion: '26.3',
      items: [{key: 'minecraft:diamond', id: 'minecraft:diamond', name: 'Diamond', count: 64, maxStack: 64}],
      shulkers: [{key: 'diamond-box', id: 'minecraft:shulker_box', itemKey: 'minecraft:diamond',
        count: 1, contents: boxContents},
        {key: 'diamond-box-2', id: 'minecraft:shulker_box', itemKey: 'minecraft:diamond',
          count: 2, contents: boxContents}]};
    if (u.pathname === '/api/v1/orders' && (opts.method || 'GET') === 'GET')
      body = {orders: [], available: {items: {'minecraft:diamond': 64}, shulkers: {}}};
    if (u.pathname === '/api/v1/orders' && opts.method === 'POST') { placed.push(JSON.parse(opts.body)); body = {}; }
    if (u.pathname === '/api/v1/quotas/me') body = {usage: {}};
    if (u.pathname === '/api/v1/bots') body = [];
    return {ok: true, status: 200, headers: new Map(), json: async () => body};
  };
  try {
    w.eval(fs.readFileSync(path.join(root, 'theme-init.js'), 'utf8'));
    w.eval(fs.readFileSync(path.join(root, 'streamer-init.js'), 'utf8'));
    w.eval(code);
    await rest();
    const doc = w.document;
    // One Shop page: shelf, cart, delivery and review steps, no picker, no box rows.
    assert(doc.querySelector('#dash-nav [data-page="shop"]'), 'shop nav');
    assert.equal(doc.querySelector('#dash-nav [data-page="orders"]'), null);
    assert.equal(doc.querySelector('#dash-nav [data-page="stash"]'), null);
    assert.equal(doc.querySelector('#order-picker'), null);
    assert.equal(doc.querySelector('#picker-body'), null);
    assert(doc.querySelector('#cart-step-1') && doc.querySelector('#cart-step-2') && doc.querySelector('#cart-step-3'), 'wizard steps');
    assert.equal(doc.querySelectorAll('#item-grid .slot').length > 0, true);
    // The shelf aggregates loose (64) and boxed (3 x 1728) diamonds.
    const slot = [...doc.querySelectorAll('#item-grid .slot')]
      .find(s => s.textContent.includes('5.2k'));
    assert(slot, 'aggregated diamond count, got: ' + doc.querySelector('#item-grid').textContent.slice(0, 120));
    // One stack into the cart from the shelf.
    [...doc.querySelectorAll('#item-grid button')].find(b => (b.getAttribute('aria-label') || '').includes('Diamond')).click();
    await rest();
    let lines = [...doc.querySelectorAll('#cart-lines .cart-line')];
    assert.equal(lines.length, 1);
    assert.equal(lines[0].querySelector('input').value, '64');
    // More than the loose stock refuses with the boxed breakdown, changing nothing.
    const input = lines[0].querySelector('input');
    input.value = '100';
    input.dispatchEvent(new w.Event('change', {bubbles: true}));
    await rest();
    assert.match(doc.querySelector('#cart-status').textContent, /Only 64 .* loose available.*5,184 in full shulkers/);
    lines = [...doc.querySelectorAll('#cart-lines .cart-line')];
    assert.equal(lines.length, 1);
    assert.equal(lines[0].querySelector('input').value, '64');
    // A full box worth plus the loose rest splits automatically: no thinking in boxes.
    input.value = '1792';
    input.dispatchEvent(new w.Event('change', {bubbles: true}));
    await rest();
    lines = [...doc.querySelectorAll('#cart-lines .cart-line')];
    assert.equal(lines.length, 2);
    const text = lines.map(l => l.textContent).join(' | ');
    assert.match(text, /packed/);
    // Placing walks the wizard: cart -> delivery -> review -> place order.
    doc.querySelector('#cart-continue').click();
    await rest();
    assert.equal(doc.querySelector('#cart-step-1').hidden, true);
    assert.equal(doc.querySelector('#cart-step-2').hidden, false);
    doc.querySelector('#cart-to-review').click();
    await rest();
    assert.equal(doc.querySelector('#cart-step-3').hidden, false);
    assert.match(doc.querySelector('#order-review').textContent, /Diamond/);
    doc.querySelector('#cart-place').click();
    await rest();
    assert.equal(placed.length, 1);
    const refs = placed[0].lines.map(l => l.type + ':' + l.key + 'x' + l.count).sort();
    assert.deepEqual(refs, ['item:minecraft:diamondx64', 'shulker:diamond-boxx1']);
    assert.deepEqual(errors, []);
  } finally {
    w.close();
  }
}
(async () => {
  await scenario();
  console.log('Shop regression passed: aggregated shelf, automatic box split, offline-ready cart.');
})().catch(e => {console.error(e); process.exitCode = 1;});
