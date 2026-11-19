#!/usr/bin/env python3
"""W-018: real physical scan identities, Manager revisions, expiry and production rescans on vanilla 26.3."""
import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('world_harness', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
require = world.require
fake_spec = importlib.util.spec_from_file_location('signed_client', world.ROOT / 'stash-manager-server/dev/fake-addon.py')
signed = importlib.util.module_from_spec(fake_spec); fake_spec.loader.exec_module(signed)


class StockHarness(world.Harness):
    def __init__(self, image):
        super().__init__(image)
        self.env['WESORT_STOCK_MAX_AGE_SECONDS'] = '60'

    def fixture(self):
        super().fixture()
        for command in [
            'setblock 30 64 3 minecraft:trapped_chest[facing=north,type=left]',
            'setblock 31 64 3 minecraft:trapped_chest[facing=north,type=right]',
            'item replace block 30 64 3 container.0 with minecraft:diamond 16',
            'item replace block 31 64 3 container.0 with minecraft:diamond 16',
            'setblock 33 64 3 minecraft:chest[facing=north]',
            'item replace block 33 64 3 container.0 with minecraft:emerald 8',
        ]:
            reply = self.rcon.command(command)
            require(not any(text in reply for text in ['Unknown', 'Incorrect', 'not loaded']), 'Fixture rejected: ' + reply)

    def physical_snapshot(self):
        before = super().physical_snapshot()
        for x in [30, 31, 33]: before[f'{x},64,3'] = self.rcon.command(f'data get block {x} 64 3 Items')
        return before

    def observe(self):
        # This stash registers ordinary/trapped/double chests and the barrel. The separate placed-shulker
        # fixture remains untouched, outside this scanner's registered source set.
        self.teleport(0.5, 2)
        self.command('overlap-open', x=0, y=64, z=3); self.command('close')
        self.ok('overlapping actual vanilla menu opens are drained without issuing a falsely attributed observation')
        for x in range(0, 30, 3):
            self.teleport(x + 0.5, 2)
            self.command('open', x=x, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=x+1); self.command('close')
        self.teleport(0.5, -1.5)
        self.command('open', x=0, y=64, z=-3, slots=27, item='minecraft:diamond', count=32); self.command('close')
        self.teleport(30.5, 2)
        self.command('open', x=30, y=64, z=3, slots=54, item='minecraft:diamond', count=16, second=31); self.command('close')
        self.teleport(33.5, 2)
        self.command('open', x=33, y=64, z=3, slots=27, item='minecraft:emerald', count=8); self.command('close')
        self.teleport(6.5, -1.5)
        self.command('open', x=6, y=64, z=-3, slots=27, item='minecraft:air', count=0); self.command('close')
        initial = self.command('state')['state']['index']
        self.teleport(0.5, 2)
        opposite = self.command('open', x=1, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=0)['state']['index']
        self.command('close')
        a = next(x for x in initial['physicalSources'] if x['parts'] == [[0, 64, 3], [1, 64, 3]])
        b = next(x for x in opposite['physicalSources'] if x['parts'] == [[0, 64, 3], [1, 64, 3]])
        require(len(a['fingerprint']) == 64 and len(b['fingerprint']) == 64 and a['id'] == b['id'] and a['fingerprint'] == b['fingerprint'], 'Double-chest identity/fingerprint depends on clicked half')
        counts = Counter()
        for item in opposite['items']: counts[item['id']] += item['count']
        require(counts['minecraft:diamond'] == 1344 and counts['minecraft:emerald'] == 8 and len(opposite['physicalSources']) == 14,
            'Two views of the same double chest duplicated inventory')
        self.ok('real normal/trapped/double chests and barrel have deterministic location identities; opposite double-chest halves produce one snapshot and no double count')
        return opposite

    def stock(self):
        status, body = self.http('/api/v1/stock'); require(status == 200, 'Stock API failed'); return body

    def wait(self, predicate, message, timeout=120):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            current = self.stock()
            if predicate(current): return current
            time.sleep(0.5)
        (self.evidence / 'last-stock-on-timeout.json').write_text(json.dumps(current, indent=2) + '\n')
        raise RuntimeError(message)

    @staticmethod
    def source(stock, x, z=3):
        return next((s for s in stock['sources'] if [x, 64, z] in s.get('parts', [])), None)

    @staticmethod
    def amount(stock, item, field):
        return sum(row[field] for row in stock['items'] if row['id'] == item)

    def pause(self): self.command('manager-pause')
    def resume(self): self.command('manager-resume')

    def rescan(self):
        status, _ = self.http('/api/v1/rescan', {'server': self.server_name, 'stash': 'e2e-fixture'})
        require(status == 200, 'Production rescan request failed')

    def click(self, state, slot):
        item = state['slots'][slot]
        return self.command('click', menu=state['containerId'], state=state['stateId'], slot=slot,
            count=item['count'], fingerprint=item['fingerprint'], type='QUICK_MOVE')['state']

    def execute(self):
        try:
            self.start_server(); self.fixture(); before = self.physical_snapshot()
            self.start_manager()
            manager_image_id = self.run(self.compose + ['images', '-q', 'manager']).strip()
            self.start_client(); self.command('stock-scan-settings')
            initial = self.observe(); self.server_name = initial['server']
            (self.evidence / 'initial-client-index.json').write_text(json.dumps(initial, indent=2) + '\n')
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            # The dedicated Manager profile must refuse trapped-chest activation: it
            # would emit a redstone signal. All 32 diamonds in that physical source
            # remain unknown/unavailable, rather than being falsely orderable.
            def safe_initial_stock(s):
                trapped = self.source(s, 30)
                safe = [row for row in s['sources'] if row is not trapped]
                return (trapped is not None and trapped['state'] == 'UNREACHABLE'
                    and len(s['sources']) == 14 and len(safe) == 13
                    and all(row['state'] == 'FRESH' for row in safe)
                    and self.amount(s, 'minecraft:diamond', 'available') == 1312
                    and self.amount(s, 'minecraft:diamond', 'unknown') == 32)
            stock = self.wait(safe_initial_stock, 'Dedicated profile did not publish fresh safe stock and block trapped-chest access')
            self.pause()
            require(safe_initial_stock(stock), 'Sensitive or cached physical inventory was counted as orderable')
            (self.evidence / 'initial-stock.json').write_text(json.dumps(stock, indent=2) + '\n')
            self.ok('13 fresh safe sources are available; dedicated profile keeps trapped-chest inventory unknown')

            # A real, fully acknowledged movement invalidates the engine's old witness even when returned immediately.
            old_engine = self.command('engine-index')['state']['engineIndex']
            previous_witness = next(s for s in old_engine['physicalSources'] if [0, 64, 3] in s['parts'])['sequence']
            self.teleport(0.5, 2)
            state = self.command('open', x=0, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=1)['state']
            moved = self.click(state, 0)
            carry = next(s['slot'] for s in moved['slots'] if s['slot'] >= 54 and s['item'] == 'minecraft:diamond' and s['count'] == 64)
            self.click(moved, carry); self.command('close')
            engine = self.command('engine-index')['state']['engineIndex']
            witness = next(s for s in engine['physicalSources'] if [0, 64, 3] in s['parts'])
            require(witness['state'] == 'STALE' or witness['sequence'] > previous_witness,
                'A physical movement reused the previous engine witness without a new server read')
            self.ok('actual QUICK_MOVE/return invalidates the old witness; only a new full server snapshot may make the source fresh again')
            heartbeat_client = signed.Client(f'http://127.0.0.1:{self.manager_port}', self.key)
            verified = self.source(self.stock(), 0)['verifiedAt']
            code, _ = heartbeat_client.post('/api/v1/heartbeat', {'server': self.server_name, 'stash': 'e2e-fixture', 'player': self.player, 'indexHash': initial['indexHash']})
            require(code == 200 and self.source(self.stock(), 0)['verifiedAt'] == verified, 'Heartbeat renewed physical freshness')

            expired = self.wait(lambda s: self.amount(s, 'minecraft:diamond', 'available') == 0, 'Source age was renewed without server reads', timeout=75)
            require(self.amount(expired, 'minecraft:diamond', 'unknown') == 1344, 'Expired stock was invented or lost instead of marked unknown')
            status, _ = self.http('/api/v1/orders', {'server': self.server_name, 'stash': 'e2e-fixture',
                'lines': [{'type': 'item', 'key': 'minecraft:diamond', 'count': 1}]})
            require(status == 400, 'Expired source allowed a new order')
            self.ok('real expiry policy marks last counts unknown and rejects a new reservation; pause/heartbeats do not renew observations')

            # Change actual server inventory while the observer is paused; the production scanner must find the delta.
            self.rcon.command('item replace block 1 64 3 container.0 with minecraft:diamond 32')
            old_revision = self.source(stock, 0)['sourceRevision']
            self.resume(); self.rescan()
            rescanned = self.wait(lambda s: (row := self.source(s, 0)) is not None and row['state'] == 'FRESH'
                and row['sourceRevision'] > old_revision and sum(i['count'] for i in row['items'] if i['id'] == 'minecraft:diamond') == 96,
                'Production scan-only engine did not verify server delta', timeout=150)
            self.ok('production StashSorter performs real rescan after expiry and observes the server delta with a newer Manager-issued revision')
            replay = signed.Client(f'http://127.0.0.1:{self.manager_port}', self.key)
            revision = self.stock()['stockRevision']
            code, reply = replay.post('/api/v1/index', initial)
            require(code == 409 and 'stockRevision' in reply, 'Delayed older physical snapshot overwrote newer source')
            require(self.stock()['stockRevision'] == revision, 'Rejected old snapshot partially changed stock revision')
            self.ok('captured real older Fabric snapshot is rejected with 409 and cannot overwrite or renew newer physical observations')

            self.rcon.command('setblock 0 65 3 minecraft:stone'); self.rcon.command('setblock 1 65 3 minecraft:stone')
            self.rescan()
            blocked = self.wait(lambda s: (row := self.source(s, 0)) is not None and row['state'] == 'UNREACHABLE',
                'Physically blocked chest was not reported unreachable', timeout=150)
            require(self.source(blocked, 0)['items'], 'Inaccessible contents were silently discarded as empty')
            self.ok('real obstructed double chest times out safely and becomes unreachable with unknown historical counts')
            self.rcon.command('setblock 0 65 3 minecraft:air'); self.rcon.command('setblock 1 65 3 minecraft:air'); self.rescan()
            self.wait(lambda s: (row := self.source(s, 0)) is not None and row['state'] == 'FRESH', 'Unblocked chest did not recover by fresh read', timeout=150)

            self.rcon.command('setblock 33 64 3 minecraft:air'); self.rescan()
            missing = self.wait(lambda s: (row := self.source(s, 33)) is not None and row['state'] == 'WITHDRAWN',
                'Removed registered container has no uncertain tombstone', timeout=150)
            require(self.amount(missing, 'minecraft:emerald', 'available') == 0 and self.amount(missing, 'minecraft:emerald', 'unknown') == 8,
                'Missing source became orderable or invented zero historical quantity')
            self.ok('real removed container retains source provenance as unknown and contributes zero orderable stock')
            self.rcon.command('setblock 33 64 3 minecraft:chest[facing=north]')
            self.rcon.command('item replace block 33 64 3 container.0 with minecraft:emerald 8')
            self.rcon.command('item replace block 1 64 3 container.0 with minecraft:diamond 64')
            self.rescan()
            self.wait(lambda s: (row := self.source(s, 33)) is not None and row['state'] == 'FRESH'
                and (chest := self.source(s, 0)) is not None and chest['state'] == 'FRESH'
                and sum(i['count'] for i in chest['items'] if i['id'] == 'minecraft:diamond') == 128,
                'Restored physical containers did not get fresh observations', timeout=150)

            self.pause(); self.run(self.compose + ['restart', 'manager'])
            for _ in range(100):
                try:
                    if self.http('/health/ready')[0] == 200: break
                except OSError: pass
                time.sleep(0.5)
            restart = self.stock()
            require(self.amount(restart, 'minecraft:diamond', 'available') == 0 and any(s['state'] == 'STALE_STARTUP' for s in restart['sources']),
                'Manager restart trusted old cached observations')
            self.resume()
            self.wait(lambda s: (row := self.source(s, 0)) is not None and row['state'] == 'FRESH',
                'Reconnect/Manager restart did not trigger real rescan', timeout=150)
            self.ok('real Manager restart preserves source history but blocks old stock until the Fabric production scanner performs new reads')
            self.pause()
            require(before == self.physical_snapshot(), 'Scan/move-return/recovery did not restore the exact server fixtures')
            self.command('screenshot'); world.shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'stock-world.png')
            report = {'result': 'PASS', 'scope': 'W-018 real vanilla 26.3 observations, source freshness and production scan-only engine',
                'checks': self.checks, 'manager_image': self.env['WESORT_IMAGE'], 'manager_image_id': manager_image_id,
                'source_commit': world.subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=world.ROOT, text=True).strip(),
                'dirty_worktree': bool(world.subprocess.check_output(['git', 'status', '--porcelain'], cwd=world.ROOT, text=True).strip()),
                'limits': 'No global physical reservation hierarchy, kit/repack or completed guild order claim; those are later stage-3 issues.'}
            (self.evidence / 'stock-result.json').write_text(json.dumps(report, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally: self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', required=True)
    StockHarness(parser.parse_args().manager_image).execute()
