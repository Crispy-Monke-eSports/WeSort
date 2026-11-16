const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const {Linter} = require('eslint');
const root = path.join(__dirname, '../stash-manager-server/src/main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const code = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
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
  console.log('Web regression passed: references, login, navigation, agent settings; retired UI/routes absent.');
})().catch(e => {console.error(e); process.exitCode = 1;});
