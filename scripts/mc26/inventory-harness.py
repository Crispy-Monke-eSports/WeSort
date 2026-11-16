#!/usr/bin/env python3
"""Real 26.3 inventory, component, block acknowledgement and network-fault scenarios."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import signal
import socket
import threading
import time

spec = importlib.util.spec_from_file_location('world_harness', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
require = world.require


class Proxy:
    """Loopback byte-stream shaper: never decodes/modifies Minecraft packets or touches other connections."""
    def __init__(self, target):
        self.target = target
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0)); self.listener.listen()
        self.port = self.listener.getsockname()[1]
        self.delay_up = self.delay_down = 0
        self.hold_up = self.hold_down = False
        self.held_up = threading.Event(); self.held_down = threading.Event()
        self.generation = 0
        self.connections = []
        self.stopped = False
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while not self.stopped:
            try:
                client, _ = self.listener.accept()
                server = socket.create_connection(('127.0.0.1', self.target), timeout=15)
                client.settimeout(None); server.settimeout(None)
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.connections += [client, server]
                generation = self.generation
                threading.Thread(target=self.forward, args=(client, server, True, generation), daemon=True).start()
                threading.Thread(target=self.forward, args=(server, client, False, generation), daemon=True).start()
            except OSError:
                if not self.stopped: time.sleep(0.05)

    def forward(self, source, target, upstream, generation):
        try:
            while not self.stopped and generation == self.generation:
                data = source.recv(65536)
                if not data: break
                while (self.hold_up if upstream else self.hold_down) and generation == self.generation and not self.stopped:
                    (self.held_up if upstream else self.held_down).set()
                    time.sleep(0.01)
                end = time.monotonic() + (self.delay_up if upstream else self.delay_down)
                while time.monotonic() < end and generation == self.generation and not self.stopped: time.sleep(0.01)
                if generation != self.generation or self.stopped: break
                target.sendall(data)
        except OSError:
            pass
        finally:
            for s in [source, target]:
                try: s.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                s.close()

    def drop(self):
        self.generation += 1
        for s in self.connections:
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            s.close()
        self.connections.clear()
        self.hold_up = self.hold_down = False

    def close(self):
        self.stopped = True; self.drop(); self.listener.close()


class InventoryHarness(world.Harness):
    def __init__(self, image):
        super().__init__(image)
        self.proxy = Proxy(self.server_port)

    def start_client(self):
        target = self.server_port
        self.server_port = self.proxy.port
        try: super().start_client()
        finally: self.server_port = target

    def close(self):
        try: super().close()
        finally: self.proxy.close()

    def fixture(self):
        super().fixture()
        for slot, item in enumerate(['chest', 'barrel', 'oak_sign']):
            reply = self.rcon.command(f'item replace block 6 64 -3 container.{slot} with minecraft:{item} 1')
            require('Replaced 1 slot' in reply, 'Staging fixture rejected: ' + reply)

    def click(self, state, slot, kind='QUICK_MOVE', allow_failure=False):
        value = state['slots'][slot]
        return self.command('click', menu=state['containerId'], state=state['stateId'], slot=slot,
            count=value['count'], fingerprint=value['fingerprint'], type=kind, allow_failure=allow_failure)

    def open_chest(self):
        self.teleport(0.5, 2.0)
        return self.command('open', x=0, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=1)['state']

    def wait_server(self, predicate, message, timeout=15):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if predicate(): return
            time.sleep(0.05)
        raise RuntimeError(message)

    def chest_halves(self):
        return [self.rcon.command(f'data get block {x} 64 3 Items') for x in [0, 1]]

    def player_items(self):
        return self.rcon.command(f'data get entity {self.player} Inventory')

    @staticmethod
    def carry_slot(state, size, item, count=1):
        return next(slot['slot'] for slot in state['slots'] if slot['slot'] >= size and slot['item'] == item and slot['count'] == count)

    def cursor_and_quick_move(self):
        state = self.open_chest()
        stale = self.command('click', menu=state['containerId'], state=state['stateId'] + 1,
            slot=0, count=64, fingerprint=state['slots'][0]['fingerprint'], type='QUICK_MOVE', allow_failure=True)
        require(not stale['success'] and 'Stale' in stale['message'], 'A stale state id was accepted')
        taken = self.click(state, 0, 'PICKUP')['state']
        require(taken['cursorCount'] == 64 and taken['slots'][0]['count'] == 0, 'Server cursor readback differs')
        require(sum('[]' in value for value in self.chest_halves()) == 1, 'Server did not remove exactly one physical half-stack')
        deposited = self.click(taken, 54, 'PICKUP')['state']
        require(deposited['cursorCount'] == 0 and deposited['slots'][54]['count'] == 64, 'Server carry-slot deposit differs')
        require('minecraft:diamond' in self.player_items(), 'Server player inventory has no transferred stack')
        restored = self.click(deposited, 54)['state']
        require(restored['slots'][0]['count'] == 64 and all('count: 64' in value for value in self.chest_halves()), 'Return did not restore both physical halves')
        self.command('close')
        require('[]' in self.player_items(), 'Test player retained inventory after return')
        (self.evidence / 'inventory-slot-map.json').write_text(json.dumps({'double_chest_menu_slot_0_is_half': 1,
            'player_main_slot_9_is_menu_slot': 54, 'after': self.chest_halves()}, indent=2) + '\n')
        self.ok('PICKUP, real cursor, player-slot mapping and QUICK_MOVE return verified by full server snapshots and independent server NBT')

    def latency_and_timeout(self):
        state = self.open_chest()
        self.proxy.delay_down = 1.0
        start = time.monotonic()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.click, state, 0)
            self.wait_server(lambda: any('[]' in value for value in self.chest_halves()), 'Delayed click never reached the server')
            require(not pending.done(), 'Action completed before its delayed server readback')
            moved = pending.result(timeout=20)['state']
        require(time.monotonic() - start >= 1.0, 'Injected network delay did not apply')
        require(moved['attemptedClicks'] == state['attemptedClicks'] + 1, 'Physical click was retried while waiting')
        self.proxy.delay_down = 0
        slot = self.carry_slot(moved, 54, 'minecraft:diamond', 64)
        restored = self.click(moved, slot)['state']
        self.ok('delayed actual server traffic blocks progression without retry or a second withdrawal')

        self.proxy.hold_down = True; self.proxy.held_down.clear()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.click, restored, 0, 'QUICK_MOVE', True)
            require(self.proxy.held_down.wait(5), 'Server replies were not held')
            self.wait_server(lambda: any('[]' in value for value in self.chest_halves()), 'Server did not process timeout scenario')
            failed = pending.result(timeout=20)
        require(not failed['success'] and 'NEEDS_RECONCILIATION' in failed['message'], 'Uncertain inventory was marked complete')
        marker = self.game_dir / 'meteor-client/wesort/inventory-sync/pending.json'
        require(marker.is_file() and marker.stat().st_mode & 0o777 == 0o600, 'No private durable pending marker')
        self.proxy.hold_down = False
        time.sleep(1.5)  # drain old responses; they may not release a failed fence
        paused = self.command('state')['state']
        require(not paused['inventoryReady'] and paused['attemptedClicks'] == restored['attemptedClicks'] + 1,
            'Late responses silently resumed or replayed the operation')
        recovered = self.command('reconcile')['state']
        require(recovered['inventoryReady'] and not marker.exists(), 'Explicit original-postcondition readback did not recover')
        require(recovered['attemptedClicks'] == paused['attemptedClicks'], 'Reconciliation replayed a physical click')
        slot = self.carry_slot(recovered, 54, 'minecraft:diamond', 64)
        self.click(recovered, slot); self.command('close')
        self.ok('missing acknowledgement pauses durably; late replies do not resume; explicit server readback recovers without replay')

    def select_item(self, state, size, item):
        menu_slot = self.carry_slot(state, size, item)
        inventory_slot = menu_slot - size - 27
        require(0 <= inventory_slot <= 8, 'Test item did not enter expected hotbar range')
        self.command('close')
        self.command('select', slot=inventory_slot)

    def break_and_pick_up(self, pos, item, fingerprint):
        x, y, z = pos
        self.teleport(x + 0.5, z - 1.5)
        self.command('break', x=x, y=y, z=z)
        reply = self.rcon.command(f'execute if block {x} {y} {z} minecraft:air run time query gametime')
        require('time is ' in reply, 'Server did not confirm the broken block as air')
        self.command('forget', x=x, y=y, z=z)
        self.teleport(x + 0.5, z + 0.5)
        for _ in range(100):
            state = self.command('state')['state']
            if any(slot['item'] == item and slot['count'] == 1 and slot['fingerprint'] == fingerprint for slot in state['slots']): return state
            time.sleep(0.1)
        raise RuntimeError('Dropped item was not picked up with the original component fingerprint')

    def shulker_round_trip(self):
        self.teleport(0.5, -1.5)
        state = self.command('open', x=0, y=64, z=-3, slots=27, item='minecraft:diamond', count=32)['state']
        fingerprint = state['slots'][1]['fingerprint']
        taken = self.click(state, 1)['state']
        self.select_item(taken, 27, 'minecraft:shulker_box')
        placed = self.command('place', x=3, y=64, z=0, block='minecraft:shulker_box')['state']
        require(placed['lastBlockAck'] >= placed['lastBlockSequence'], 'No native block acknowledgement')
        observed = self.command('open', x=3, y=64, z=0, slots=27, item='minecraft:dirt', count=64, capture=False)['state']
        require(observed['slots'][0]['count'] == 64, 'Placed shulker contents differ')
        require('count: 64' in self.rcon.command('data get block 3 64 0 Items'), 'Server shulker content differs')
        self.command('close')
        self.break_and_pick_up((3, 64, 0), 'minecraft:shulker_box', fingerprint)
        self.teleport(0.5, -1.5)
        state = self.command('open', x=0, y=64, z=-3, slots=27, item='minecraft:diamond', count=32)['state']
        restored = self.click(state, self.carry_slot(state, 27, 'minecraft:shulker_box'))['state']
        require(restored['slots'][1]['fingerprint'] == fingerprint, 'Shulker components changed after placement/break/pickup')
        self.command('close')
        self.ok('named filled shulker place/open/break/pickup/return preserves server-confirmed contents and component fingerprint')

    def block_round_trips(self):
        for source_slot, item in enumerate(['minecraft:chest', 'minecraft:barrel', 'minecraft:oak_sign']):
            self.teleport(6.5, -1.5)
            state = self.command('open', x=6, y=64, z=-3, slots=27, item='minecraft:chest', count=1, capture=False)['state']
            fingerprint = state['slots'][source_slot]['fingerprint']
            taken = self.click(state, source_slot)['state']
            self.select_item(taken, 27, item)
            self.command('place', x=6, y=64, z=0, block=item)
            if item == 'minecraft:oak_sign':
                self.command('close')  # close the placement editor before explicitly opening the controlled write
                lines = ['WeSort', 'Verified', '26.3', '']
                self.command('sign', x=6, y=64, z=0, lines=lines)
                actual = self.rcon.command('data get block 6 64 0 front_text.messages')
                require(all(text in actual for text in lines if text), 'Sign text not present in authoritative server NBT')
                (self.evidence / 'sign-text.json').write_text(json.dumps({'expected': lines, 'server': actual}, indent=2) + '\n')
            else:
                self.command('open', x=6, y=64, z=0, slots=27, item='minecraft:air', count=0, capture=False)
                self.command('close')
            self.break_and_pick_up((6, 64, 0), item, fingerprint)
            self.teleport(6.5, -1.5)
            # The staging first slot may be empty while its chest item is in the player inventory.
            first_item = 'minecraft:air' if source_slot == 0 else 'minecraft:chest'
            first_count = 0 if source_slot == 0 else 1
            state = self.command('open', x=6, y=64, z=-3, slots=27, item=first_item, count=first_count, capture=False)['state']
            restored = self.click(state, self.carry_slot(state, 27, item))['state']
            require(restored['slots'][source_slot]['fingerprint'] == fingerprint, 'Placed block item did not return unchanged')
            self.command('close')
        loose = self.rcon.command('execute if entity @e[type=minecraft:item] run time query gametime')
        require('time is ' not in loose, 'Unexpected loose item entities remain after pickup')
        self.ok('real chest/barrel/sign place/open/write/break/pickup round trips; no loose drops or component changes')

    def limited_range(self):
        self.teleport(0.5, -1.5)
        rejected = self.command('open', x=27, y=64, z=3, slots=54, item='minecraft:diamond', count=64, allow_failure=True)
        require(not rejected['success'] and 'range' in rejected['message'], 'Out-of-range operation was accepted')
        self.ok('loaded but unreachable container refused before interaction')

    def observe(self):
        super().observe()
        self.cursor_and_quick_move()
        self.latency_and_timeout()
        self.shulker_round_trip()
        self.block_round_trips()
        self.limited_range()
        # Re-read the physical sources after all mutations; never publish pre-move snapshots as current stock.
        return super().observe()

    def disconnect_and_restart(self, baseline_index):
        state = self.open_chest()
        marker = self.game_dir / 'meteor-client/wesort/inventory-sync/pending.json'
        require(not marker.exists(), 'A previous verified action left a pending journal')
        self.proxy.hold_up = True; self.proxy.held_up.clear()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.click, state, 0)
            self.wait_server(lambda: marker.exists() and self.proxy.held_up.is_set(), 'Click was not durably recorded before held transmission')
            require(all('count: 64' in value for value in self.chest_halves()), 'Held click already changed server inventory')
            self.proxy.drop()  # discard held bytes; do not forward them after reconnect
            os.killpg(self.client.pid, signal.SIGTERM)
            try: self.client.wait(timeout=15)
            except Exception: os.killpg(self.client.pid, signal.SIGKILL); self.client.wait(timeout=5)
            try:
                pending.result(timeout=5)
                raise RuntimeError('Disconnected unconfirmed action was reported complete')
            except RuntimeError as e:
                require('reported complete' not in str(e), str(e))
        require(marker.is_file(), 'Unconfirmed intent was forgotten on disconnect')
        (self.evidence / 'pending-inventory.json').write_bytes(marker.read_bytes())
        (self.evidence / 'client.log').rename(self.evidence / 'client-before-restart.log')
        self.start_client()
        restarted = self.command('state')['state']
        require(not restarted['inventoryReady'] and restarted['attemptedClicks'] == 0,
            'Restart silently enabled or replayed a pending physical action')
        recovered = self.command('reconcile', allow_failure=True)
        require(not recovered['success'] and 'NEEDS_RECONCILIATION' in recovered['message'],
            'Unchanged server pre-state was accepted as the original postcondition')
        blocked = self.command('attempt-unsafe')['state']
        require(blocked['attemptedClicks'] == 0, 'Production mixin permitted an unsafe replay')
        require(all('count: 64' in value for value in self.chest_halves()) and '[]' in self.player_items(),
            'Source or player count changed across dropped click, restart or rejected reconciliation')
        query = world.urllib.parse.urlencode({'server': baseline_index['server'], 'stash': 'e2e-fixture'})
        status, stored = self.http('/api/v1/index?' + query)
        require(status == 200, 'Published index disappeared after the interrupted action')
        # W-018 hashes observation session/revision/freshness too. A valid new read or stale
        # transition may change that hash without changing inventory. Compare the physical
        # postconditions instead: variants, group quantities and full per-container contents.
        def quantities(index, field):
            return {entry['key']: entry['count'] for entry in index.get(field, [])}
        for field in ['items', 'shulkers']:
            require(quantities(stored, field) == quantities(baseline_index, field),
                'An unverified action changed published ' + field + ' quantities/fingerprints')
        def sources(index):
            return {source['id']: (source['fingerprint'], source['dimension'], source['parts'],
                quantities(source, 'items'), quantities(source, 'shulkers')) for source in index.get('physicalSources', [])}
        require(sources(stored) == sources(baseline_index),
            'An unverified action changed a published full physical snapshot')
        self.ok('disconnect before delivery and real client restart keep pending intent, reject unsafe replay and refuse a false reconciliation')

    def execute(self):
        try:
            self.start_server(); self.fixture()
            before = self.physical_snapshot()
            (self.evidence / 'server-before.json').write_text(json.dumps(before, indent=2) + '\n')
            self.start_manager(); self.start_client()
            index = self.observe()
            self.command('screenshot')
            world.shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'fixture.png')
            self.verify_manager_index(index)
            self.disconnect_and_restart(index)
            after = self.physical_snapshot()
            require(after == before, 'Server container data changed after inventory/fault scenarios')
            (self.evidence / 'server-after.json').write_text(json.dumps(after, indent=2) + '\n')
            self.ok('all original server inventory totals/components restored after movement, block operations, lag, timeout and restart')
            result = {'result': 'PASS', 'scope': 'W-015 real vanilla 26.3 inventory and block operations',
                'checks': self.checks, 'manager_image': self.env['WESORT_IMAGE'],
                'source_commit': world.subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=world.ROOT, text=True).strip(),
                'dirty_worktree': bool(world.subprocess.check_output(['git', 'status', '--porcelain'], cwd=world.ROOT, text=True).strip()),
                'limits': 'No full guild-order, kit/repack pipeline or multiagent correctness claim. Full physical operation/custody journal remains W-023.'}
            (self.evidence / 'inventory-result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally:
            self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', default=world.DEFAULT_IMAGE)
    options = parser.parse_args()
    InventoryHarness(options.manager_image).execute()
