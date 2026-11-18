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
  const kitWrites = [];
  const physicalReviews = [];
  const guildUpdates = [];
  const orderReviews = [];
  let guild = {name: "<img src=x> Guild", description: "Shared stock", revision: 1};
  w.addEventListener('error', e => errors.push(e.error || e.message));
  const globals = Object.fromEntries(Object.getOwnPropertyNames(w).map(x => [x, 'readonly']));
  Object.assign(globals, {fetch: 'readonly', EventSource: 'readonly', ResizeObserver: 'readonly',
    requestAnimationFrame: 'readonly', cancelAnimationFrame: 'readonly'});
  assert.deepEqual(new Linter().verify(code, [{languageOptions: {ecmaVersion: 2022, globals},
    rules: {'no-undef': 'error'}}]).map(e => `${e.line}: ${e.message}`), []);
  const bot = {botId: 'b1', player: 'Agent', online: true, lastSeen: Date.now(),
    plugin: {id: 'wesort', version: 'test'}, caps: ['settings', 'activity'], scopes: ['plugin']};
  w.fetch = async (url, opts = {}) => {
    requests.push(url);
    const u = new URL(url, w.location.href);
    assert(!/planner|console|viewer/.test(u.pathname), `Retired endpoint: ${url}`);
    assert(!u.searchParams.has('scope') || u.searchParams.get('scope') === 'plugin');
    let body = {};
    if (u.pathname === '/api/v1/auth/state') body = {loggedIn, minLength: 14,
      elevatedUntil: Date.now() + 600000, user: {id: 'owner', name: 'Owner', owner: true}};
    if (u.pathname === '/api/v1/guild') {
      if (opts.method === 'POST') {
        const update = JSON.parse(opts.body);
        guildUpdates.push(update);
        guild = {name: update.name, description: update.description, approvalPolicy: update.approvalPolicy, revision: guild.revision + 1};
      }
      body = guild;
    }
    if (u.pathname === '/api/v1/stock') body = {stockRevision: 12, maxAgeMillis: 300000,
      items: [{server: 'test', id: 'minecraft:diamond', name: '<img src=x onerror=alert(1)>', observed: 64, reserved: 10, available: 54, unknown: 0}],
      stashes: [{server: 'test', stash: 'fixture', items: [{id: 'minecraft:diamond', observed: 64, reserved: 10, available: 54, unknown: 0}]}],
      sources: [{id: 'c:test', slotSchema: 1, slotCount: 1, server: 'test', stash: 'fixture', dimension: 'minecraft:overworld', parts: [[1, 64, 2]],
        state: 'STALE_AGE', sourceRevision: 11, verifiedAt: 1000}]};
    if (u.pathname === '/api/v1/stock/source') body = {state: 'STALE_AGE', snapshot: {positions: [{path:'0/26',id:'minecraft:diamond_sword',count:1,copies:1,kind:'ITEM',components:'<img src=x onerror=alert(1)>'}]}};
    if (u.pathname === '/api/v1/stashes') body = [{server:'test', stash:'fixture', online:true}];
    if (u.pathname === '/api/v1/orders') body = {orders: [{
      id:'guild-order', status:'pending', state:'RESERVED', purpose:'GUILD', approvalState:'PENDING',
      recipient:'FixtureRecipient', deliveryMode:'PLAYER', comment:'Projekt Å <img src=x onerror=alert(1)>',
      lines:[{type:'item', key:'minecraft:diamond', count:2}]
    }]};
    if (u.pathname === '/api/v1/orders/guild-order/approval') orderReviews.push(JSON.parse(opts.body));
    if (u.pathname === '/api/v1/bots') body = [bot];
    if (u.pathname === '/api/v1/kits/editor') body = {
      templates: [{ revision: 7, template: { name: '<img src=x onerror=alert(1)>', fingerprint: 'exact-v7', slots: [[0,0,32],[26,0,16]] }, slotFingerprints: {0:'variant-one',26:'variant-one'} }],
      variants: [{ id: 'minecraft:diamond', fingerprint: 'variant-one', maxStack: 64, components: '<script>bad()</script>' }],
      destinations: [{ id: 'c:home', stash: 'fixture', dimension: 'minecraft:overworld', parts: [[1,64,2]] }]
    };
    if (u.pathname === '/api/v1/kits' && opts.method === 'POST') { kitWrites.push(JSON.parse(opts.body)); body = {}; }
    if (u.pathname === '/api/v1/kits/jobs' && opts.method === 'POST') { kitWrites.push(JSON.parse(opts.body)); body = {}; }
    if (u.pathname === '/api/v1/kits/jobs' && opts.method !== 'POST') body = {jobs: [{name:'Raid',revision:7,state:'VERIFYING',id:'job-one',agentEstimate:{built:1,possible:2,exact:false,shortage:'<img src=x>',at:1234}}]};
    if (u.pathname === '/api/v1/physical') body = {operations: [{
      id: 'op-one', orderId: 'o-one', revision: 9, checksum: 'op-checksum',
      kind: 'REPACK', managerState: 'NEEDS_RECONCILIATION', interruptedPhase: 'BREAK',
      server: '<img src=x>', dimension: 'minecraft:overworld',
      possibleOrphans: [{x:1, y:64, z:2, block:'minecraft:shulker_box'}]
    }]};
    if (u.pathname === '/api/v1/physical/inspect') physicalReviews.push(JSON.parse(opts.body));
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
      const review = w.document.querySelector('#order-list .order-card');
      assert.match(review.textContent, /Awaiting approval.*Confirmed recipient · FixtureRecipient.*Projekt Å <img/);
      assert.equal(review.querySelector('img'), null, 'Order comments must remain plain text');
      Array.from(review.querySelectorAll('button')).find(button => button.textContent === 'Approve').click();
      await settle();
      assert.deepEqual(orderReviews, [{decision:'approve'}]);
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
      w.document.querySelector('#dash-nav [data-page="settings"]').click(); await settle();
      assert.equal(w.document.querySelector('#kit-admin').hidden, false);
      assert.equal(w.document.querySelector('#guild-admin').hidden, false);
      assert.match(w.document.querySelector('#guild-stock-title').textContent, /<img src=x> Guild.*Shared stock/);
      assert.equal(w.document.querySelector('#guild-stock-title img'), null);
      w.document.querySelector('#guild-name').value = 'New guild';
      w.document.querySelector('#guild-approval').value = 'GUILD';
      w.document.querySelector('#guild-form').dispatchEvent(new w.Event('submit', {cancelable: true}));
      await settle();
      assert.deepEqual(guildUpdates, [{name:'New guild', description:'Shared stock', approvalPolicy:'GUILD', expectedRevision:1}]);
      assert.equal(w.document.querySelector('#guild-approval').value, 'GUILD');
      assert.match(w.document.querySelector('#guild-stock-title').textContent, /New guild/);
      assert.equal(w.document.querySelector('#physical-admin').hidden, false);
      assert.match(w.document.querySelector('#physical-list').textContent, /NEEDS_RECONCILIATION.*BREAK.*Order o-one/);
      assert.equal(w.document.querySelector('#physical-list img'), null);
      assert.match(w.document.querySelector('#physical-list [data-sensitive]').textContent, /1, 64, 2/);
      w.document.querySelector('#physical-list button').click(); await settle();
      assert.deepEqual(physicalReviews, [{operation:'op-one', revision:9, checksum:'op-checksum'}]);
      w.document.querySelector('#kit-server').value = 'test'; w.document.querySelector('#kit-load').click(); await settle();
      w.document.querySelector('#kit-list button').click();
      const kitRows = w.document.querySelectorAll('#kit-slots .kit-slot'); assert.equal(kitRows.length, 27);
      assert.equal(kitRows[0].querySelector('select').value, 'variant-one');
      assert.equal(kitRows[26].querySelector('input').value, '16');
      assert.equal(w.document.querySelector('#kit-list img'), null);
      assert.equal(w.document.querySelector('#kit-slots script'), null);
      // The first slot's listener must update its own count input, independently of slot 27.
      kitRows[0].querySelector('select').value = ''; kitRows[0].querySelector('select').dispatchEvent(new w.Event('change'));
      assert.equal(kitRows[0].querySelector('input').disabled, true); assert.equal(kitRows[26].querySelector('input').disabled, false);
      kitRows[0].querySelector('select').value = 'variant-one'; kitRows[0].querySelector('select').dispatchEvent(new w.Event('change'));
      w.document.querySelector('#kit-editor').dispatchEvent(new w.Event('submit', {cancelable:true})); await settle();
      assert.deepEqual(kitWrites[0].layout, [{slot:0,variant:'variant-one',count:32},{slot:26,variant:'variant-one',count:16}]);
      assert.equal(kitWrites[0].expectedRevision,7); assert.equal(kitWrites[0].server,'test');
      w.document.querySelector('#kit-list button').click();
      w.document.querySelector('#kit-work-x').value='2'; w.document.querySelector('#kit-work-y').value='64'; w.document.querySelector('#kit-work-z').value='0';
      w.document.querySelector('#kit-pack').dispatchEvent(new w.Event('submit', {cancelable:true})); await settle();
      assert.equal(kitWrites[1].revision,7); assert.equal(kitWrites[1].fingerprint,'exact-v7'); assert.equal(kitWrites[1].destination,'c:home');
      assert.deepEqual(kitWrites[1].workspace,{dimension:'minecraft:overworld',x:2,y:64,z:0});
      assert.match(w.document.querySelector('#kit-jobs').textContent,/VERIFYING/);
      assert.match(w.document.querySelector('#kit-jobs').textContent,/1 finished; at least 2 buildable/);
      assert.equal(w.document.querySelector('#kit-jobs img'),null);
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
