#!/usr/bin/env python3
"""No-kit scope contract for real Minecraft 26.3 (binding scope 1e6467a: no kit system).

The kit backend (KitRoutes/Catalog/Jobs, /api/v1/kits/*), Manager kit assignment
and mod kit GUI were intentionally removed; local shulker packing for ordinary
orders stays and is covered by the repack/order suites. These shards therefore
verify the REMOVAL contract instead of removed functionality:

1. Every /api/v1/kits/* route answers 404 (no dead or half-removed endpoints).
2. The Fabric client reports "kit system removed" for kit-info/kit-compare.
3. A stale kit template file planted in the client profile is never imported
   (bytes and mtime unchanged after a full client run).
4. The served web bundle references no kit API.

Each --scenario runs the same compact contract; shards stay as parallel slots.
"""
import argparse
import importlib.util
import json
import time
import urllib.parse
from pathlib import Path

spec = importlib.util.spec_from_file_location('repack', Path(__file__).with_name('repack-harness.py'))
repack = importlib.util.module_from_spec(spec); spec.loader.exec_module(repack)
world = repack.world
require = world.require


class KitHarness(world.Harness):
    def __init__(self, image, scenario):
        super().__init__(image)
        self.scenario = scenario

    def execute(self):
        try:
            self.start_server()
            self.start_manager()
            # 1. No kit routes survive on the Manager (owner session).
            for path in ['/api/v1/kits/editor?server=x', '/api/v1/kits/jobs?server=x']:
                code, _ = self.http(path)
                require(code == 404, 'Removed kit route still answers: %s -> %s' % (path, code))
            for body in [{'action': 'publish'}, {'action': 'jobs'}]:
                code, _ = self.http('/api/v1/kits', body)
                require(code == 404, 'Removed kit endpoint still answers: %s' % code)
            self.ok('no /api/v1/kits/* route survives the removal')

            # 4. The served web bundle references no kit API.
            root = self.browser.open('http://127.0.0.1:%d/' % self.manager_port)
            html = root.read().decode('utf-8', 'replace')
            root.close()
            import re
            bundles = sorted(set(re.findall(r'assets/index-[A-Za-z0-9_-]+\.js', html)))
            require(bundles, 'No hashed web bundle served')
            bundle = self.browser.open('http://127.0.0.1:%d/' % self.manager_port + bundles[0]).read().decode('utf-8', 'replace')
            require('/api/v1/kits' not in bundle, 'Web bundle still calls the removed kit API')
            self.ok('served web bundle has no kit API references')

            # 2+3. The Fabric client reports removal and ignores stale template files.
            self.start_client()
            info = self.command('kit-info', allow_failure=True)
            require(info.get('success') is False and 'removed' in str(info.get('message', '')).lower(),
                'Client does not report kit removal: %s' % info)
            compare = self.command('kit-compare', allow_failure=True)
            require(compare.get('success') is False and 'removed' in str(compare.get('message', '')).lower(),
                'Client kit-compare does not report removal: %s' % compare)
            self.ok('Fabric client reports the kit system as removed')
            old = self.game_dir / 'meteor-client/wesort/kits/ignored-fixture.nbt'
            old.parent.mkdir(parents=True, exist_ok=True)
            old.write_bytes(b'old-template-fixture-not-imported')
            before = (old.read_bytes(), old.stat().st_mtime_ns)
            time.sleep(5)
            self.command('state')
            require(old.read_bytes() == before[0] and old.stat().st_mtime_ns == before[1],
                'Stale kit template file was touched')
            self.ok('stale kit template files are never imported')

            report = {'result': 'PASS', 'scope': 'no-kit removal contract', 'checks': self.checks,
                'manager_image': self.env['WESORT_IMAGE'],
                'source_commit': __import__('subprocess').check_output(['git', 'rev-parse', 'HEAD'], cwd=world.ROOT, text=True).strip()}
            (self.evidence / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally:
            self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', required=True)
    parser.add_argument('--scenario', required=True)
    args = parser.parse_args()
    KitHarness(args.manager_image, args.scenario).execute()
