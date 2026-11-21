#!/usr/bin/env python3
"""Crash recovery at a durable phase boundary in real Minecraft 26.3."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time

spec = importlib.util.spec_from_file_location('repack', Path(__file__).with_name('repack-harness.py'))
repack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack)
require = repack.require
PHASES = ('SELECT', 'PLACE', 'OPEN', 'TRANSFER', 'VERIFY', 'BREAK', 'PICKUP', 'STORE')


class JournalHarness(repack.RepackHarness):
    def __init__(self, image, phase):
        super().__init__(image, 'mixed')
        self.phase = phase
        self.journal = self.game_dir / 'meteor-client/wesort/physical-journal/active.json'

    def wait_for_review(self, operation):
        end = time.monotonic() + 35
        last = None
        while time.monotonic() < end:
            status, result = self.http('/api/v1/physical')
            require(status == 200, 'Owner journal list unavailable')
            last = result
            row = next((row for row in result['operations'] if row['id'] == operation), None)
            if row and row['managerState'] == 'NEEDS_RECONCILIATION':
                return row
            time.sleep(.25)
        # Name the miss: dump every Manager row (id/revision/state) so a wrong-id upload,
        # a wrong-server rejection or a stuck state is decidable from evidence, not a bare timeout.
        (self.evidence / 'physical-rows-on-timeout.json').write_text(json.dumps(last, indent=2) + '\n')
        raise RuntimeError('Manager did not flag the interrupted operation: %s' % json.dumps(
            [{k: row.get(k) for k in ('id', 'revision', 'managerState', 'inspectionState', 'lastSeen')}
             for row in (last or {}).get('operations', [])]))

    def snapshot_after_restart(self, row):
        snapshot = self.snapshots()
        drops = self.rcon.command('data get entity @e[type=minecraft:item,limit=1] Item')
        if 'has the following entity data' in drops:
            snapshot['drop'] = drops
        for box in row['possibleOrphans']:
            pos = ' '.join(str(box[axis]) for axis in 'xyz')
            if 'Test passed' in self.rcon.command(f'execute if block {pos} {box["block"]}'):
                snapshot['standing'] = self.rcon.command(f'data get block {pos} Items')
        return snapshot

    @staticmethod
    def canonical_snapshot(snapshot):
        # Raw RCON strings wobble (SNBT key order, Inventory list order, entity choice):
        # compare parsed, Slot-keyed, sorted semantic form. Tool wear while breaking is
        # expected (same exemption as totals()); every other component must match exactly.
        # Unparseable values fall back to raw strings so nothing is silently skipped.
        snbt = repack.snbt

        def norm(node):
            if isinstance(node, dict):
                out = {key: norm(value) for key, value in node.items()}
                # Tool wear while breaking is expected (same exemption as totals()):
                # packed item metadata stays exact, pickaxe damage is ignored.
                if isinstance(node.get('id'), str) and node['id'].endswith('_pickaxe'):
                    comps = out.get('components')
                    if isinstance(comps, dict):
                        comps.pop('minecraft:damage', None)
                return {key: out[key] for key in sorted(out)}
            if isinstance(node, list):
                # Top-level inventories use 'Slot'; nested packed container/bundle
                # children use lowercase 'slot' (server-snbt.py): normalize both.
                if node and all(isinstance(e, dict) and ('Slot' in e or 'slot' in e) for e in node):
                    def slot_key(entry):
                        return entry.get('Slot', entry.get('slot'))
                    return {'slot:' + str(slot_key(e)): norm(e)
                            for e in sorted(node, key=slot_key)}
                return [norm(e) for e in node]
            return node

        out = {}
        for key, text in snapshot.items():
            try:
                out[key] = json.dumps(norm(snbt.items(text)), sort_keys=True, separators=(',', ':'))
            except Exception:
                out[key] = 'raw:' + text
        return out

    def require_same_world(self, first, second):
        a, b = self.canonical_snapshot(first), self.canonical_snapshot(second)
        if a == b:
            return
        diff = {key: {'before': first.get(key), 'after': second.get(key),
                      'canonical_before': a.get(key), 'canonical_after': b.get(key)}
                for key in sorted(set(a) | set(b)) if a.get(key) != b.get(key)}
        (self.evidence / 'snapshot-diff.json').write_text(json.dumps(diff, indent=2) + '\n')
        raise RuntimeError('World items moved after reconnect without an execution permit: %s'
                           % sorted(diff))

    def execute(self):
        try:
            self.start_server()
            self.fixture()
            self.start_manager()
            self.start_client()
            self.configure()
            before = self.snapshots()
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            self.command('arm-physical-crash', phase=self.phase)
            self.command('sort-start', **self.settings)
            marker = self.control / 'physical-crash-ready'
            deadline = time.monotonic() + 90
            while not marker.exists():
                if not (time.monotonic() < deadline and self.client.poll() is None):
                    armed = sorted(self.control.glob('physical-crash-*'))
                    try:
                        stalled = self.command('state')['state']
                    except Exception as failed:
                        stalled = {'state_error': str(failed)}
                    stall = {
                        'armed_phase': self.phase,
                        'durable_markers': [path.name for path in armed],
                        'client_state': stalled,
                    }
                    # Decisive server ground truth for a PICKUP/BREAK stall: is the
                    # shulker still standing, dropped as an item, or gone?
                    try:
                        pos = (stalled or {}).get('repackPosition')
                        if pos:
                            stall['server_block_at_repack'] = self.rcon.command(
                                'data get block %d %d %d' % tuple(pos))
                            stall['server_drops'] = self.rcon.command(
                                'data get entity @e[type=minecraft:item,limit=5]')
                    except Exception as failed:
                        stall['server_probe_error'] = str(failed)
                    (self.evidence / 'arming-stall.json').write_text(json.dumps(
                        stall, indent=2, default=str) + '\n')
                    raise RuntimeError('The armed durable phase was not reached '
                                       '(last durable markers: %s)' % [path.name for path in armed])
                time.sleep(.025)
            os.killpg(self.client.pid, signal.SIGKILL)
            self.client.wait(timeout=10)
            crashed = json.loads(self.journal.read_text())
            require(crashed['phase'] == self.phase, 'Wrong durable phase at process kill')
            (self.evidence / 'physical-before-restart.json').write_text(json.dumps(crashed, indent=2) + '\n')
            (self.evidence / 'client.log').rename(self.evidence / 'client-before-restart.log')
            self.run(self.compose + ['restart', 'manager'])
            self.start_client()
            initial = self.command('state')['state']
            require(initial['physicalJournal'] == 'NEEDS_RECONCILIATION' and initial['attemptedClicks'] == 0,
                    'Restart reopened automation or replayed a click')
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            row = self.wait_for_review(crashed['id'])
            require(row['interruptedPhase'] == self.phase, 'Manager omitted the interrupted phase')
            require('events' not in row and 'leaseFingerprint' not in row, 'Owner summary leaked private evidence')
            if self.phase in ('OPEN', 'TRANSFER', 'VERIFY', 'BREAK', 'PICKUP', 'STORE'):
                placed = [block for event in crashed['events'] if event['type'] == 'INTENT'
                          for block in event['evidence'].get('blocks', []) if 'shulker_box' in block['block']]
                require(placed, 'The delayed rotation callback sent a placement without a durable intent')
            if self.phase in ('OPEN', 'TRANSFER', 'VERIFY', 'BREAK'):
                require(row['possibleOrphans'], 'Manager did not list the possible standing shulker')
            if self.phase == 'SELECT':
                status, _ = self.http('/api/v1/physical/inspect', {
                    'operation': row['id'], 'revision': row['revision'], 'checksum': row['checksum']
                })
                require(status == 200, 'Owner could not request an inventory-only inspection')
                end = time.monotonic() + 20
                while time.monotonic() < end:
                    _, reply = self.http('/api/v1/physical')
                    inspected = next(r for r in reply['operations'] if r['id'] == row['id'])
                    if inspected.get('inspectionState') == 'COMPLETED':
                        require(inspected['managerState'] == 'FAILED', 'No-mutation inspection falsely completed repack')
                        self.ok('Owner-approved fresh inventory verified that the SELECT operation sent no mutation')
                        break
                    time.sleep(.25)
                else:
                    raise RuntimeError('Safe SELECT recovery did not complete')
            # A crash-recovered operation stays fenced after its no-mutation proof: the
            # terminal FAILED state authorizes nothing new. Only an explicit operator
            # release (off-to-on toggle, explicit sort-start) resumes automation — never a
            # mirror round-trip, inspection result, publish or rescan.
            fenced = self.command('state')['state']
            require(fenced['physicalJournal'] in ('NEEDS_RECONCILIATION', 'WAITING_MANAGER_RECEIPT'),
                    'Client left the recovery fence without an explicit operator release: %s'
                    % fenced['physicalJournal'])
            require(fenced.get('sortingPermitted', False) is False,
                    'Sorter re-armed without an explicit operator release')
            require(fenced['attemptedClicks'] == 0, 'Client replayed an inventory click')
            after = self.snapshot_after_restart(row)
            # Soak past the 5s automation-permit window: any re-armed sorting would move
            # the world here, and the canonical comparison below would name the delta.
            time.sleep(8)
            self.require_same_world(after, self.snapshot_after_restart(row))
            require(self.command('state')['state']['attemptedClicks'] == 0, 'Client replayed an inventory click')
            require(self.totals(before) == self.totals(after), 'Crash changed authoritative recursive totals or metadata')
            self.ok(self.phase + ' survived client and Manager restart without a duplicate physical mutation')
            (self.evidence / 'result.json').write_text(json.dumps({
                'result': 'PASS', 'phase': self.phase, 'checks': self.checks,
                'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repack.world.ROOT, text=True).strip(),
                'server_sha1': repack.world.SERVER_SHA1, 'manager_operation': row,
            }, indent=2) + '\n')
            print(f'Evidence: {self.evidence}', flush=True)
        finally:
            self.close()


class DirectHarness(JournalHarness):
    def __init__(self, image, phase):
        super().__init__(image, phase)
        self.proxy = repack.network.Proxy(self.server_port)

    def manager_server_name(self):
        # The client connects through the interfering proxy, so it reports the
        # proxy address as its server: the Manager must be configured with
        # exactly that string or every agent call 409s as the wrong server.
        return '127.0.0.1:' + str(self.proxy.port)

    def execute(self):
        try:
            self.start_server()
            repack.world.Harness.fixture(self)
            self.start_manager()
            self.start_client()
            inv = repack.network.InventoryHarness
            state = inv.open_chest(self)
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            self.proxy.hold_down = True
            with ThreadPoolExecutor(max_workers=1) as pool:
                click = pool.submit(inv.click, self, state, 0)
                inv.wait_server(self, lambda: self.journal.exists() and
                    sum('[]' in value for value in inv.chest_halves(self)) == 1,
                    'The source was not debited on the real server before acknowledgment was lost')
                self.proxy.drop()
                self.proxy.hold_down = False
                os.killpg(self.client.pid, signal.SIGKILL)
                self.client.wait(timeout=10)
                try:
                    click.result(timeout=5)
                except RuntimeError:
                    pass
                else:
                    raise RuntimeError('The unacknowledged move was reported complete')
            crashed = json.loads(self.journal.read_text())
            require(crashed['state'] != 'COMPLETED', 'Move completed while server frames were held')
            (self.evidence / 'direct-before-restart.json').write_text(json.dumps(crashed, indent=2) + '\n')
            (self.evidence / 'client.log').rename(self.evidence / 'client-before-restart.log')
            self.run(self.compose + ['restart', 'manager'])
            self.start_client()
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            row = self.wait_for_review(crashed['id'])
            status, _ = self.http('/api/v1/physical/inspect', {
                'operation': row['id'], 'revision': row['revision'], 'checksum': row['checksum']
            })
            require(status == 200, 'Owner could not request exact source and inventory readback')
            end = time.monotonic() + 35
            while time.monotonic() < end:
                _, reply = self.http('/api/v1/physical')
                inspected = next(r for r in reply['operations'] if r['id'] == row['id'])
                if inspected.get('inspectionState') == 'COMPLETED':
                    require(inspected['managerState'] == 'COMPLETED', 'Manager kept a verified action fenced')
                    break
                if inspected.get('inspectionState') == 'FAILED':
                    raise RuntimeError(inspected.get('inspectionDetail', 'Inspection failed'))
                time.sleep(.25)
            else:
                raise RuntimeError('Verified postcondition did not clear the operation fence')
            state = self.command('state')['state']
            # The Manager marks the Owner inspection COMPLETED on receipt of the
            # client's read-only verification. The client still needs one
            # background agent upload round-trip to mirror the new revision and
            # clear WAITING_MANAGER_RECEIPT. Poll briefly instead of asserting
            # the receipt synchronously; any click replay still fails below.
            receipt_end = time.monotonic() + 30
            while not (state['attemptedClicks'] == 0 and state['physicalJournal'] == 'READY'):
                require(state['attemptedClicks'] == 0,
                        'Read-only recovery replayed movement or left the agent paused')
                require(state['physicalJournal'] in ('READY', 'WAITING_MANAGER_RECEIPT'),
                        'Read-only recovery replayed movement or left the agent paused: ' + str(state['physicalJournal']))
                require(time.monotonic() < receipt_end,
                        'Read-only recovery replayed movement or left the agent paused')
                time.sleep(0.5)
                state = self.command('state')['state']
            require(sum('[]' in value for value in inv.chest_halves(self)) == 1 and 'count: 64' in inv.player_items(self),
                    'Exact native source and player totals changed during inspection')
            # The Manager's asynchronous index rescan can own a different
            # container menu even after the physical receipt is READY. The
            # Owner inspection proof above is complete: explicitly pause the
            # Manager scanner so it cannot race the next independent direct click.
            self.command('manager-pause')
            self.command('close')
            isolated = self.command('state')['state']
            require(isolated['attemptedClicks'] == 0 and isolated['physicalJournal'] == 'READY',
                    'Recovery changed before the next direct move')
            require(isolated['containerId'] == 0,
                    'Manager background scanner still owns an inventory screen')
            # A new, explicitly requested move proves the same agent can operate again.
            menu = self.command('open', x=0, y=64, z=3, slots=54, item='minecraft:air', count=0, second=1)['state']
            carry = inv.carry_slot(menu, 54, 'minecraft:diamond', 64)
            inv.click(self, menu, carry)
            require(all('count: 64' in value for value in inv.chest_halves(self)) and '[]' in inv.player_items(self),
                    'Resumed agent did not return exactly the one previously debited stack')
            self.ok('same HYBRID agent verified the accepted click and resumed without double pickup')
            (self.evidence / 'result.json').write_text(json.dumps({
                'result': 'PASS', 'phase': 'DIRECT', 'checks': self.checks,
                'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repack.world.ROOT, text=True).strip(),
                'server_sha1': repack.world.SERVER_SHA1, 'manager_operation': inspected,
            }, indent=2) + '\n')
            print(f'Evidence: {self.evidence}', flush=True)
        finally:
            self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=PHASES + ('DIRECT',), required=True)
    parser.add_argument('--manager-image', default='wesort-manager:journal-tests')
    arguments = parser.parse_args()
    harness = DirectHarness if arguments.phase == 'DIRECT' else JournalHarness
    harness(arguments.manager_image, arguments.phase).execute()
