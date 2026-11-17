const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const {Linter} = require('eslint');
const root = path.join(__dirname, '../stash-manager-server/src/main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const code = fs.readFileSync(path.join(root, 'app.js'), 'utf8');

const crypto = require('node:crypto');
const brandingRoot = path.join(__dirname, '../branding');
const officialSvg = fs.readFileSync(path.join(brandingRoot, 'wesort.svg'), 'utf8');
const webSvg = fs.readFileSync(path.join(root, 'wesort.svg'), 'utf8');
const lockupSvg = fs.readFileSync(path.join(root, 'wesort-lockup.svg'), 'utf8');
const brandingLockup = fs.readFileSync(path.join(brandingRoot, 'wesort-lockup.svg'), 'utf8');
const gitBlobSha = content => crypto.createHash('sha1')
  .update(Buffer.from('blob ' + Buffer.byteLength(content) + '\\0'.replace('\\0', '\0')))
  .update(content)
  .digest('hex');
assert.equal(gitBlobSha(officialSvg), '0288c2962a8eb9572f70d45f15da78bd2cc798ec',
  'WeSort SVG must remain byte-identical to the official WeAB.nu logo');
assert.equal(webSvg, officialSvg, 'Web favicon must use the official WeAB.nu SVG');
assert.equal(brandingLockup, lockupSvg, 'Website and branding lockup must stay identical');
assert.equal(gitBlobSha(lockupSvg), 'a02e1a50008cd0af1e420ad90b2046c546219511',
  'The audited vector lockup has changed');
assert.match(officialSvg, /id="brand-color"[^>]*fill="#E66A00"/);
assert.match(lockupSvg, /id="brand-suffix"/);
for (const letter of 'sort') assert.ok(lockupSvg.includes('id="suffix-' + letter + '"'));
const sourceArtwork = officialSvg.split('  <g id="brand-sign">')[1].split('</svg>')[0];
assert.ok(lockupSvg.includes(sourceArtwork),
  'The source WE sign paths, corners, shading and letters must remain unchanged');
assert.equal(new JSDOM(html).window.document.querySelector('.brand-logo')?.getAttribute('src'),
  '/wesort-lockup.svg');
assert.equal(new JSDOM(html).window.document.querySelector('.auth-logo')?.getAttribute('src'),
  '/wesort-lockup.svg');
const modIcon = fs.readFileSync(path.join(__dirname, '../src/main/resources/assets/wesort/icon.png'));
assert.equal(crypto.createHash('sha256').update(modIcon).digest('hex'),
  '577369272ce568c18015e0164e7dae1f12cc09415ea84d1aa9ead75ab45d951b',
  'Fabric icon must be rendered from the approved lockup');

// Keep the Minecraft non-affiliation notice in the unauthenticated and authenticated views.
const notice = 'NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.';
const noticeDoc = new JSDOM(html).window.document;
for (const id of ['minecraft-disclaimer-dashboard', 'minecraft-disclaimer-login']) {
  const element = noticeDoc.getElementById(id);
  assert(element, 'Missing Minecraft disclaimer: ' + id);
  assert.equal(element.textContent.trim(), notice);
  assert.equal(element.hidden, false);
}

const settle = () => new Promise(resolve => setTimeout(resolve, 30));

async function scenario(loggedIn) {
  const dom = new JSDOM(html, {url: 'http://localhost:8765', runScripts: 'outside-only'});
  const w = dom.window;
  const errors = [];
  const requests = [];
  w.addEventListener('error', e => errors.push(e.error || e.message));
  const globals = Object.fromEntries(Object.getOwnPropertyNames(w).map(x => [x, 'readonly']));
  Object.assign(globals, {fetch: 'readonly', EventSource: 'readonly', ResizeObserver: 'readonly',
    requestAnimationFrame: 'readonly', cancelAnimationFrame: 'readonly'});
  assert.deepEqual(new Linter().verify(code, [{languageOptions: {ecmaVersion: 2022, globals},
    rules: {'no-undef': 'error'}}]).map(e => `${e.line}: ${e.message}`), []);
  const bot = {botId: 'b1', player: 'Agent', online: true, lastSeen: Date.now(),
    plugin: {id: 'wesort', version: 'test'}, caps: ['settings', 'activity'], scopes: ['plugin']};
  w.fetch = async url => {
    requests.push(url);
    const u = new URL(url, w.location.href);
    assert(!/planner|console|viewer/.test(u.pathname), `Retired endpoint: ${url}`);
    assert(!u.searchParams.has('scope') || u.searchParams.get('scope') === 'plugin');
    let body = {};
    if (u.pathname === '/api/v1/auth/state') body = {loggedIn, minLength: 14,
      elevatedUntil: Date.now() + 600000, user: {id: 'owner', name: 'Owner', owner: true}};
    if (u.pathname === '/api/v1/stock') body = {stockRevision: 12, maxAgeMillis: 300000,
      items: [{server: 'test', id: 'minecraft:diamond', name: '<img src=x onerror=alert(1)>', observed: 64, reserved: 10, available: 54, unknown: 0}],
      stashes: [{server: 'test', stash: 'fixture', items: [{id: 'minecraft:diamond', observed: 64, reserved: 10, available: 54, unknown: 0}]}],
      sources: [{id: 'c:test', slotSchema: 1, slotCount: 1, server: 'test', stash: 'fixture', dimension: 'minecraft:overworld', parts: [[1, 64, 2]],
        state: 'STALE_AGE', sourceRevision: 11, verifiedAt: 1000}]};
    if (u.pathname === '/api/v1/stock/source') body = {state: 'STALE_AGE', snapshot: {positions: [{path:'0/26',id:'minecraft:diamond_sword',count:1,copies:1,kind:'ITEM',components:'<img src=x onerror=alert(1)>'}]}};
    if (u.pathname === '/api/v1/stashes') body = [];
    if (u.pathname === '/api/v1/bots') body = [bot];
    if (u.pathname === '/api/v1/users') body = {owner: {name: 'Owner'}, users: [], presets: {}};
    if (u.pathname.endsWith('/settings')) body = {schema: [], values: {}, schemaHash: 'a', valuesHash: 'b'};
    if (u.pathname.endsWith('/activity')) body = {now: [], events: [], modules: [], plugins: []};
    return {ok: true, status: 200, headers: new Map(), json: async () => body};
  };
  const legacy = {'stashManager.user': 'OLD', 'stashManager.page': 'console', 'sm-theme': 'corrupt', 'stashManager.streamer': '1'};
  for (const [key, value] of Object.entries(legacy)) w.localStorage.setItem(key, value);
  const read = w.Storage.prototype.getItem;
  const write = w.Storage.prototype.setItem;
  const remove = w.Storage.prototype.removeItem;
  w.Storage.prototype.getItem = function(key) {
    assert(key.startsWith('wesort.'), `Legacy browser key read: ${key}`);
    return read.call(this, key);
  };
  w.Storage.prototype.setItem = function(key, value) {
    assert(key.startsWith('wesort.'), `Legacy browser key changed: ${key}`);
    return write.call(this, key, value);
  };
  w.Storage.prototype.removeItem = function(key) {
    assert(key.startsWith('wesort.'), `Legacy browser key deleted: ${key}`);
    return remove.call(this, key);
  };
  w.requestAnimationFrame = () => 0;
  w.matchMedia = () => ({matches: false, addEventListener() {}});
  w.HTMLElement.prototype.scrollIntoView = () => {};
  try {
    w.eval(fs.readFileSync(path.join(root, 'theme-init.js'), 'utf8'));
    w.eval(fs.readFileSync(path.join(root, 'streamer-init.js'), 'utf8'));
    w.eval(code);
    await settle();
    assert.equal(w.document.querySelector('#auth-screen').hidden, loggedIn);
    for (const name of ['planner', 'proxy', 'console', 'viewer']) {
      assert.equal(w.document.querySelector(`[data-page="${name}"]`), null);
      assert.equal(w.document.querySelector(`#page-${name}`), null);
    }
    if (loggedIn) {
      for (const name of ['orders', 'bots', 'activity', 'settings', 'stash']) {
        const button = w.document.querySelector(`#dash-nav [data-page="${name}"]`);
        assert(button, name);
        button.click();
        await settle();
        if (['bots', 'activity', 'settings'].includes(name)) {
          assert.equal(w.document.querySelector(`#page-${name}`).hidden, false, name);
        }
      }
      assert(requests.includes('/api/v1/bots'));
      assert(requests.includes('/api/v1/stock'));
      assert.equal(w.document.querySelector('#stock-balances tr').textContent,
        'test<img src=x onerror=alert(1)>6410540');
      assert.equal(w.document.querySelector('#stock-stashes tr').textContent, 'fixtureminecraft:diamond6410540');
      assert.equal(w.document.querySelector('#stock-balances img'), null, 'Stock labels must remain plain text');
      assert.match(w.document.querySelector('#stock-sources').textContent, /minecraft:overworld.*1, 64, 2.*Observation expired.*revision 11/);
      const details = w.document.querySelector('#stock-sources details');
      details.open = true; details.dispatchEvent(new w.Event('toggle')); await settle();
      assert(requests.some(x => x.startsWith('/api/v1/stock/source?id=')));
      assert.match(details.textContent, /0\/26minecraft:diamond_sword/);
      assert.equal(details.querySelector('img'), null, 'Component patches must remain plain text');
      assert(requests.some(x => x.includes('/settings?scope=plugin')));
    }
    assert.deepEqual(errors, []);
    for (const [key, value] of Object.entries(legacy)) assert.equal(read.call(w.localStorage, key), value);
  } finally {
    w.close();
  }
}
(async () => {
  await scenario(false);
  await scenario(true);
  console.log('Web regression passed: official WeAB.nu WeSort branding, references, login, navigation and retired UI/routes.');
})().catch(e => {console.error(e); process.exitCode = 1;});
