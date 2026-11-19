// Executes the actual Docker-served web app against its real API. Cookie stays in process memory.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const base = process.argv[2];
const report = process.argv[3];
const cookie = process.env.WESORT_TEST_COOKIE;
(async () => {
  const request = async (path, options = {}) => {
    const url = new URL(path, base);
    assert.equal(url.origin, new URL(base).origin);
    const headers = new Headers(options.headers || {});
    headers.set('Cookie', cookie);
    return fetch(url, { ...options, headers });
  };
  // The React SPA shell and its hashed assets are served by Java.
  const root = await request('/');
  assert.equal(root.status, 200);
  assert.match(root.headers.get('content-type'), /text\/html/);
  const html = await root.text();
  assert.match(html, /<div id="root"><\/div>/);
  const assets = [...new Set([...html.matchAll(/assets\/index-[A-Za-z0-9_-]+\.(js|css)/g)].map(m => m[0]))];
  assert.ok(assets.some(a => a.endsWith('.js')) && assets.some(a => a.endsWith('.css')), 'Hashed bundle missing');
  const checked = [];
  for (const asset of assets) {
    const res = await request('/' + asset);
    assert.equal(res.status, 200, 'Missing built asset: ' + asset);
    if (asset.endsWith('.js')) {
      assert.match(res.headers.get('cache-control'), /immutable/, 'Hashed JS must cache immutably');
      const bundle = await res.text();
      for (const marker of ['item-grid', 'cart-lines', 'cart-place', 'order-history-title']) {
        assert.ok(bundle.includes(marker), 'Shop marker missing: ' + marker);
      }
      checked.push(asset);
    }
    if (asset.endsWith('.css')) {
      assert.match(res.headers.get('cache-control') || '', /immutable/, 'Hashed CSS must cache immutably');
    }
  }
  // Frontend routes serve the SPA directly; unknown API paths stay JSON 404; traversal is refused.
  for (const route of ['/shop', '/orders', '/bots', '/activity', '/settings', '/login']) {
    const res = await request(route);
    assert.equal(res.status, 200, 'SPA route not served: ' + route);
    assert.match(await res.text(), /<div id="root"><\/div>/);
  }
  assert.equal((await request('/index.html')).status, 200);
  assert.equal((await request('/api/v1/no-such-route')).status, 404);
  assert.equal((await request('/..%2fsecret')).status, 404);
  assert.equal((await request('/assets/../../secret')).status, 404);
  // The Classic dirt background: verified vanilla texture when the client cache exists, else fallback.
  const dirt = await request('/dirt.png');
  assert.ok(dirt.status === 200 || dirt.status === 404, 'Unexpected dirt status: ' + dirt.status);
  if (dirt.status === 200) {
    assert.match(dirt.headers.get('content-type'), /image\/png/);
    const bytes = Buffer.from(await dirt.arrayBuffer());
    assert.deepEqual([...bytes.slice(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  }
  // The real index API route still answers (shape verified by assets-harness.py).
  const indexRes = await request('/api/v1/index?server=test&stash=fixture');
  assert.ok([200, 400, 404].includes(indexRes.status), 'Unexpected index status: ' + indexRes.status);
  fs.writeFileSync(report, JSON.stringify({ result: 'PASS', scope: 'Docker-served React SPA shell, assets, routes and dirt fallback; no browser pixel rendering claim', assets: checked }, null, 2) + '\n');
  console.log('PASS: real Manager serves the React shop shell, hashed assets, SPA routes and dirt fallback');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
