// Static regression for the React/TypeScript storefront (served by Java from /web).
// No JSDOM execution: the app is a Vite bundle. Asserts branding/license parity,
// the SPA shell, hashed-asset wiring and that no retired implementation remains.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.join(__dirname, '../stash-manager-server/src/main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

const brandingRoot = path.join(__dirname, '../branding');
const officialSvg = fs.readFileSync(path.join(brandingRoot, 'wesort.svg'), 'utf8');
const webSvg = fs.readFileSync(path.join(root, 'wesort.svg'), 'utf8');
const lockupSvg = fs.readFileSync(path.join(root, 'wesort-lockup.svg'), 'utf8');
const brandingLockup = fs.readFileSync(path.join(brandingRoot, 'wesort-lockup.svg'), 'utf8');

const gitBlobSha = content => crypto.createHash('sha1')
  .update(Buffer.from('blob ' + Buffer.byteLength(content) + '\0'))
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

// No competing implementation: the monolithic app.js/style.css and legacy init scripts are gone.
for (const legacy of ['app.js', 'style.css', 'theme-init.js', 'streamer-init.js']) {
  assert.equal(fs.existsSync(path.join(root, legacy)), false, `Retired web file still served: ${legacy}`);
}

// SPA shell: #root mount, Vite hashed assets, brand lockup reference.
assert.match(html, /<div id="root"><\/div>/, 'SPA mount missing');
const assets = [...html.matchAll(/assets\/index-[A-Za-z0-9_-]+\.(js|css)/g)].map(m => m[0]);
assert.ok(assets.some(a => a.endsWith('.js')), 'Hashed JS bundle missing from index.html');
assert.ok(assets.some(a => a.endsWith('.css')), 'Hashed CSS bundle missing from index.html');
for (const asset of new Set(assets)) {
  assert.ok(fs.existsSync(path.join(root, asset)), `Referenced asset missing: ${asset}`);
}
// The Minecraft non-affiliation notice and the Classic dirt fallback ship in the bundle.
const bundle = assets.filter(a => a.endsWith('.js')).map(a => fs.readFileSync(path.join(root, a), 'utf8')).join('\n');
const styles = assets.filter(a => a.endsWith('.css')).map(a => fs.readFileSync(path.join(root, a), 'utf8')).join('\n');
assert.ok(bundle.includes('wesort-lockup.svg') || html.includes('wesort-lockup.svg'), 'Brand lockup not referenced');
const notice = 'NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.';
assert.ok(bundle.includes(notice), 'Minecraft disclaimer missing from the bundle');
assert.ok(styles.includes('/dirt.png') && styles.includes('dirt.svg'), 'Classic dirt background/fallback missing');
// Retired endpoints and the old guild-stock DOM must not come back through the bundle.
for (const retired of ['/api/v1/stock', '/api/v1/planner', '/api/v1/console', '/api/v1/viewer', 'stock-balances', 'picker-body']) {
  assert.equal(bundle.includes(retired), false, `Retired reference still in bundle: ${retired}`);
}
// Unified shop markers from PR #89 survive the migration.
for (const marker of ['item-grid', 'cart-lines', 'cart-place', 'order-history-title', 'order-audit']) {
  assert.ok(bundle.includes(marker), `Shop marker missing from bundle: ${marker}`);
}

console.log('Web regression passed: branding, SPA shell, hashed assets, dirt fallback, disclaimer, no retired implementation.');
