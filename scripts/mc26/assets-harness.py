#!/usr/bin/env python3
"""Fresh 26.3 scan, component variants, Docker icons, real web API/DOM and in-game Stash Window."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import time

spec = importlib.util.spec_from_file_location('world_harness', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
require = world.require


class AssetsHarness(world.Harness):
    def fixture(self):
        super().fixture()
        components = 'minecraft:enchantments={"minecraft:unbreaking":1},minecraft:damage=3,minecraft:custom_name={text:"E2E sword"}'
        variants = [
            'diamond_sword[minecraft:damage=4,minecraft:custom_name={text:"E2E second sword"}]',
            'bundle[minecraft:bundle_contents=[{id:"minecraft:gold_ingot",count:2}]]',
            'shulker_box[minecraft:container=[{slot:1,item:{id:"minecraft:stone",count:32}}],minecraft:custom_name={text:"E2E second box"}]',
            'diamond_sword[' + components + ',minecraft:custom_data={variant:1}]',
            'bundle[minecraft:bundle_contents=[{id:"minecraft:iron_ingot",count:8}],minecraft:custom_data={variant:2}]',
            'copper_sword',
            'red_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:dirt",count:64}}]]',
        ]
        for slot, item in enumerate(variants, 4):
            reply = self.rcon.command(f'item replace block 0 64 -3 container.{slot} with minecraft:{item} 1')
            require('Replaced 1 slot' in reply, 'Component fixture rejected: ' + reply)

    def verify_index(self, index):
        counts = Counter()
        for item in index['items']: counts[item['id']] += item['count']
        expected = {'minecraft:diamond': 1312, 'minecraft:dirt': 144, 'minecraft:iron_ingot': 16,
            'minecraft:diamond_sword': 3, 'minecraft:shulker_box': 2, 'minecraft:bundle': 3,
            'minecraft:stone': 32, 'minecraft:gold_ingot': 2, 'minecraft:copper_sword': 1, 'minecraft:red_shulker_box': 1}
        require(dict(counts) == expected, 'Fresh scan totals differ: ' + str(dict(counts)))
        for item, size in [('diamond_sword', 3), ('bundle', 3)]:
            variants = [entry for entry in index['items'] if entry['id'] == 'minecraft:' + item]
            require(len(variants) == size and len({entry['key'] for entry in variants}) == size, 'Distinct component variants merged: ' + item)
        require(len(index['shulkers']) == 3 and len({box['key'] for box in index['shulkers']}) == 3, 'Shulker content/color groups merged')
        require(all(item['tab'] != 'other' for item in index['items']), 'Real creative categories missing')
        swords = [item for item in index['items'] if item['id'] == 'minecraft:diamond_sword']
        require(sum(item.get('enchanted', False) for item in swords) == 2 and all(item.get('damage', 0) > 0 for item in swords), 'Name/enchantment/damage metadata missing')
        self.ok('fresh scan preserves custom data, bundle contents, damage, enchantments, names and distinct colored/filled shulker groups')
        return counts

    def manager_index(self, source):
        self.command('publish', url='http://127.0.0.1:' + str(self.manager_port), key=self.key)
        query = world.urllib.parse.urlencode({'server': source['server'], 'stash': 'e2e-fixture'})
        for _ in range(120):
            status, index = self.http('/api/v1/index?' + query)
            if status == 200: break
            time.sleep(0.5)
        else: raise RuntimeError('Real signed agent did not publish')
        self.verify_index(index)
        require({x['key']: x['count'] for x in source['items']} == {x['key']: x['count'] for x in index['items']}, 'API v1 normalization lost variant identities')
        (self.evidence / 'manager-index.json').write_text(json.dumps(index, indent=2) + '\n')
        response = self.browser.open('http://127.0.0.1:' + str(self.manager_port) + '/api/v1/index?' + query)
        etag = response.headers.get('ETag'); response.close()
        require(etag is not None, 'API v1 ETag missing')
        request = world.urllib.request.Request('http://127.0.0.1:' + str(self.manager_port) + '/api/v1/index?' + query, headers={'If-None-Match': etag})
        try: self.browser.open(request); raise RuntimeError('API conditional request did not return 304')
        except world.urllib.error.HTTPError as error: require(error.code == 304, 'Wrong conditional API response')
        return index

    def icons(self, index):
        folder = self.evidence / 'icons'; folder.mkdir()
        digests = {}
        for item in sorted({x['id'] for x in index['items']}):
            namespace, path = item.split(':', 1)
            response = self.browser.open(f'http://127.0.0.1:{self.manager_port}/icon/{namespace}/{path}.png?v=26.3-g1', timeout=90)
            data = response.read()
            require(response.status == 200 and response.headers.get_content_type() == 'image/png' and data.startswith(b'\x89PNG') and struct.unpack('>II', data[16:24]) == (64, 64), 'No real 26.3 rendered PNG: ' + item)
            (folder / (path + '.png')).write_bytes(data); digests[item] = hashlib.sha256(data).hexdigest()
        require(digests['minecraft:shulker_box'] != digests['minecraft:red_shulker_box'], 'Colored shulker icons collided')
        unknown = self.browser.open(f'http://127.0.0.1:{self.manager_port}/icon/minecraft/wesort_unknown_item.png?v=26.3-g1')
        unknown_data = unknown.read()
        require(unknown.headers.get_content_type() == 'image/png' and struct.unpack('>II', unknown_data[16:24]) == (16, 16) and hashlib.sha256(unknown_data).hexdigest() not in digests.values(), 'Unknown icon was not the safe missing-texture fallback')
        (self.evidence / 'icon-hashes.json').write_text(json.dumps(digests, indent=2) + '\n')
        self.ok('fresh Docker downloads checksum-verified official 26.3 assets, renders every fixture PNG including shulkers, and unknown ID returns safe missing-texture PNG')

    def web(self):
        env = dict(self.env)
        # HTTPCookieProcessor is located by type; never persist the Owner session.
        processor = next(handler for handler in self.browser.handlers if isinstance(handler, world.urllib.request.HTTPCookieProcessor))
        env['WESORT_TEST_COOKIE'] = '; '.join(cookie.name + '=' + cookie.value for cookie in processor.cookiejar)
        result = world.subprocess.run([env.get('NODE', 'node'), str(world.ROOT / 'web-tests/live-mc26.cjs'),
            f'http://127.0.0.1:{self.manager_port}', str(self.evidence / 'web-result.json')], env=env, cwd=world.ROOT,
            text=True, capture_output=True, timeout=90)
        require(result.returncode == 0, 'Real web UI failed: ' + result.stderr[-2000:])
        self.ok('actual Docker-served web app executes against real API and renders variant, damage, enchantment and filled shulker DOM')

    def window(self, expected):
        state = self.command('window-open')['state']
        counts = Counter()
        for item in state['windowItems']: counts[item['id']] += item['count']
        require(counts == expected, 'Passive Stash Window scan totals differ: ' + str(dict(counts)))
        require(len(state['windowShulkers']) == 3, 'Client window merged filled shulker groups')
        self.command('screenshot')
        world.shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'stash-window.png')
        (self.evidence / 'window-index.json').write_text(json.dumps({'items': state['windowItems'], 'shulkers': state['windowShulkers']}, indent=2) + '\n')
        self.ok('real Stash Window renders passive fresh 26.3 captures with matching totals, variants and shulker contents')

    def execute(self):
        try:
            # Explicit integration-test consent, rather than relying on a production default.
            # This private, disposable Docker instance only; no Minecraft game assets enter the image.
            self.env['WESORT_EULA'] = 'true'
            self.start_server(); self.fixture(); before = self.physical_snapshot()
            self.start_manager(); self.start_client(); self.command('component-identity'); self.ok('actual item CODEC distinguishes NBT byte/int custom data while canonicalizing compound order'); self.command('window-capture')
            source = self.observe(); expected = self.verify_index(source)
            index = self.manager_index(source); self.icons(index); self.web()
            # The signed Manager publish deliberately starts a production rescan. That
            # scan opens/closes Minecraft container screens asynchronously, so it can
            # replace the passive Stash Window before the GUI assertion observes it.
            # The Manager index and live web checks are already complete; isolate the
            # read-only GUI check from the independent mutating scan controller.
            self.command('manager-pause')
            self.command('sort-stop')
            self.window(expected)
            require(before == self.physical_snapshot(), 'Read-only resource/UI verification changed server inventory')
            log = (self.evidence / 'client.log').read_text()
            require(not re.search(r'\[(?:Render thread|main)/ERROR\].*(?:WeSortAddon|Mixin|wesort)', log), 'Real window logged an error')
            result = {'result': 'PASS', 'scope': 'W-017 fresh 26.3 scan, real Docker icons/web API and actual in-game Stash Window',
                'checks': self.checks, 'manager_image': self.env['WESORT_IMAGE'],
                'source_commit': world.subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=world.ROOT, text=True).strip(),
                'dirty_worktree': bool(world.subprocess.check_output(['git', 'status', '--porcelain'], cwd=world.ROOT, text=True).strip()),
                'limits': 'Web DOM execution uses JSDOM; Minecraft rendering is a real native client screenshot. Full guild engine remains later stages.'}
            (self.evidence / 'assets-result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally: self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', required=True, help='Explicit local candidate or published verified image')
    AssetsHarness(parser.parse_args().manager_image).execute()
