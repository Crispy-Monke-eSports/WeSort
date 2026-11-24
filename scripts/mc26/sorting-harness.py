#!/usr/bin/env python3
"""Physical tier sorting, blocked capacity and protected sources in vanilla 26.3."""
import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path
import time
from urllib.parse import urlencode

spec = importlib.util.spec_from_file_location('world', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
snbt_spec = importlib.util.spec_from_file_location('snbt', Path(__file__).with_name('server-snbt.py'))
snbt = importlib.util.module_from_spec(snbt_spec); snbt_spec.loader.exec_module(snbt)
require = world.require

class SortingHarness(world.Harness):
    locations = list(range(0, 33, 3))
    ordinary_stock = [
        ('wheat_seeds', 23, 7),
        ('dirt', 16, 4),
        ('ender_pearl', 11, 5),
    ]

    def fixture(self):
        super().fixture()
        self.rcon.command('fill -1 64 -4 35 66 4 minecraft:air')
        self.rcon.command('kill @e[type=minecraft:item]')
        for x in self.locations:
            self.rcon.command(f'setblock {x} 64 3 minecraft:barrel')
        self.setitem(0, 0, 'diamond', 32)
        self.setitem(0, 2, 'diamond_sword[minecraft:custom_name={text:"Do not rename"},minecraft:damage=3,minecraft:enchantments={"minecraft:unbreaking":1}]', 1)
        self.setitem(0, 3, 'enchanted_golden_apple', 4)
        self.setitem(0, 4, 'potion[minecraft:potion_contents={potion:"minecraft:strength"}]', 1)
        self.setitem(0, 5, 'spyglass', 1)
        for slot, (item, count, _) in enumerate(self.ordinary_stock):
            self.setitem(0, 6 + slot, item, count)
            # Reserve an exact named variant held only in the ordinary stock barrel.
            self.setitem(6, slot, f'{item}[minecraft:custom_name={{text:"Order {item}"}}]', count)
        self.setitem(0, 9, 'oak_sign', 3)
        for slot in range(27): self.setitem(3, slot, 'diamond', 60 if slot == 0 else 64)
        self.setitem(12, 0, 'diamond', 5); self.setitem(15, 0, 'diamond', 7); self.setitem(30, 0, 'diamond', 9)
        self.setitem(15, 1, 'shulker_box[minecraft:custom_name={text:"Reserved kit"},minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:8}}]]', 1)
        self.ok('mixed named/enchanted/damaged/potion/slang fixture, full diamond destination with one partial stack, excluded and protected kit storage')
    def setitem(self, x, slot, item, count):
        reply = self.rcon.command(f'item replace block {x} 64 3 container.{slot} with minecraft:{item} {count}')
        require('Replaced 1 slot' in reply, 'Rejected sorting fixture item: ' + reply)
    def nbt(self, x): return self.rcon.command(f'data get block {x} 64 3 Items')
    def packed_count(self, x, item):
        # Real server-side NBT, including exact nested stack counts in the
        # shulker's minecraft:container component. No client cache or mock.
        return sum(
            inner['item'].get('count', 1)
            for outer in snbt.items(self.nbt(x))
            if outer['id'].endswith('shulker_box')
            for inner in outer.get('components', {}).get('minecraft:container', [])
            if inner['item']['id'] == 'minecraft:' + item
        )
    def count(self, x, item):
        # Independent server commands, with each stack queried separately to avoid the RCON 4KiB frame limit.
        import re
        total = 0
        for slot in range(27):
            raw = self.rcon.command(f'data get block {x} 64 3 Items[{{Slot:{slot}b,id:"minecraft:{item}"}}].count')
            match = re.search(r': (\d+)\s*$', raw)
            if match: total += int(match.group(1))
        return total
    def observe(self, first=None):
        for x in self.locations:
            self.teleport(x + 0.5, 2)
            item, count = ('diamond', 32) if x == 0 else ('diamond', 60) if x == 3 else ('diamond', 5) if x == 12 else ('diamond', 7) if x == 15 else ('diamond', 9) if x == 30 else ('air', 0)
            if x == 6:
                item, count, _ = self.ordinary_stock[0]
            if first is not None:
                item, count = first.get(x, ('air', 0))
            self.command('open', x=x, y=64, z=3, slots=27, item='minecraft:' + item, count=count)
            self.command('close')
        for x, mark in [(0, {'dropOff': True}), (12, {'excluded': True}), (15, {'protected': True}), (30, {'delivery': True})]:
            self.command('sort-mark', x=x, y=64, z=3, **mark)
    def authoritative_totals(self):
        # Independent RCON witnesses, not the sorter's cache. Include the player's
        # carried inventory so incomplete but safe moves cannot appear as losses.
        sums = Counter()
        for x in self.locations:
            sums.update(snbt.totals(snbt.items(self.nbt(x))))
        sums.update(snbt.totals(snbt.items(
            self.rcon.command('data get entity ' + self.player + ' Inventory'))))
        return dict(sums)
    def stock(self):
        code, value = self.http('/api/v1/stock'); require(code==200,'Stock API failed'); return value
    def verify_ordinary_stock_orders(self):
        source = next(row for row in self.stock()['sources'] if [6, 64, 3] in row['parts'])
        query = urlencode({'id': source['id']})
        status, detail = self.http('/api/v1/stock/source?' + query)
        require(status == 200 and detail['state'] == 'FRESH', 'Fixture stock was not freshly observed')
        positions = {slot['path']: slot for slot in detail['snapshot']['positions']}
        observed = {slot['path']: slot for slot in detail['slots']}
        server_items = {stack['Slot']: stack for stack in snbt.items(self.nbt(6))}
        before = self.nbt(6)
        lines = []
        expected = {}
        for slot, (item, total, amount) in enumerate(self.ordinary_stock):
            path = str(slot)
            native_stack = server_items[slot]
            require(native_stack['id'] == 'minecraft:' + item and native_stack['count'] == total,
                    'Unexpected server fixture stack for ' + item)
            require('Order ' + item in json.dumps(native_stack['components']['minecraft:custom_name']),
                    'Named fixture lost its server-side component: ' + item)
            require(observed[path]['count'] == total, 'Manager observed the wrong number of ' + item)
            key = positions[path]['key']
            require(key != 'minecraft:' + item, 'Manager did not distinguish the named variant: ' + item)
            lines.append({'type': 'item', 'key': key, 'count': amount})
            expected[path] = ('item:' + key, amount, observed[path]['fingerprint'])
        status, response = self.http('/api/v1/orders', {'stash': 'e2e-fixture', 'lines': lines})
        require(status == 200, 'Manager could not reserve seeds, dirt and pearls')
        order = response['order']
        allocations = order['allocations']
        require(len(allocations) == len(expected)
                and all(entry['source'] == source['id'] for entry in allocations)
                and {entry['path']: (entry['ref'], entry['count'], entry['fingerprint'])
                     for entry in allocations} == expected,
                'Order allocations did not preserve exact slot paths, quantities and fingerprints: '
                + json.dumps(allocations))
        status, cancelled = self.http('/api/v1/orders/' + order['id'] + '/cancel', {})
        require(status == 200 and cancelled['order']['status'] == 'cancelled' and self.nbt(6) == before,
                'Reservation cancellation did not preserve the server inventory')
        self.ok('fresh ordinary seeds, dirt and pearls reserve exact physical slots and fingerprints; cancellation preserves inventory')

    def order(self):
        return self.http('/api/v1/orders', {'server':self.server_name,'stash':'e2e-fixture',
            'lines':[{'type':'item','key':'minecraft:diamond','count':1}]})
    def wait(self, predicate, message, timeout=180):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if predicate(): return
            time.sleep(0.5)
        raise RuntimeError(message)
    def stop_sorter_safely(self, timeout=90):
        """Wait for an actual verified idle boundary, not merely the target item count.

        The sorter may still be sending clicks when the final inventory
        assertion becomes true. Stopping it then can strand a durable physical
        journal and make the next fixture phase fail reconciliation.
        """
        deadline = time.monotonic() + timeout
        last = {}
        while time.monotonic() < deadline:
            response = self.command('sort-stop-when-idle', allow_failure=True)
            last = response.get('state', {})
            if response['success']:
                require(not last['sorterActive'] and last['inventoryReady']
                        and last['physicalJournal'] == 'READY',
                        'Sorter did not stop at a verified physical boundary')
                require('[]' in self.rcon.command('data get entity ' + self.player + ' Inventory'),
                        'Verified stop left the unfinished sorting batch in player inventory')
                return
            require(not last.get('journalBlocked') and not last.get('inventoryFailure'),
                    'Sorter needs physical reconciliation; refusing to hide a real inventory failure: '
                    + str(last.get('inventoryFailure') or last.get('physicalJournal')))
            time.sleep(.25)
        raise RuntimeError('Sorter never became safe to stop: '
                           + str({key: last.get(key) for key in
                                  ('physicalJournal', 'inventoryReady', 'containerId', 'shulkerPhase')}))

    def execute(self):
        try:
            self.start_server(); self.fixture(); self.start_manager()
            manager_image_id = self.run(self.compose + ['images','-q','manager']).strip()
            self.start_client(); self.observe(); self.command('sort-config', mode='Tiers')
            self.settle_test_menus()
            baseline_totals = self.authoritative_totals()
            excluded, protected, outbound = self.nbt(12), self.nbt(15), self.nbt(30)
            sword_components = self.rcon.command('data get block 0 64 3 Items[{id:"minecraft:diamond_sword"}].components').split(': ',1)[-1]
            potion_components = self.rcon.command('data get block 0 64 3 Items[{id:"minecraft:potion"}].components').split(': ',1)[-1]
            self.server_name = self.command('state')['state']['server']
            self.command('publish',url=f'http://127.0.0.1:{self.manager_port}',key=self.key,
                         holdPhysicalSorting=True)
            self.wait(lambda: (stock:=self.stock())['sources'] and all(source['state'] in ['FRESH','PROTECTED'] for source in stock['sources'])
                and any(row['id']=='minecraft:diamond' and row['available']>0 for row in stock['items'])
                and self.command('state')['state']['containerId']==0,
                'Fresh physical index did not reach Manager')
            self.verify_ordinary_stock_orders()
            status, reservation = self.order(); require(status==200,'Could not create actual reservation')
            before_reserved = {x:self.nbt(x) for x in self.locations}
            before_clicks = self.command('state')['state']['attemptedClicks']
            # The dedicated agent now reclaims foreign menus even in scan-only
            # mode. Suspend its autonomous loop only for this read-only fixture
            # inspection, without disabling the signed Manager or reservation.
            self.command('manager-inspection-hold', hold=True)
            self.settle_test_menus()
            self.teleport(0.5, 2); self.command('sort-config')
            self.command('open', x=0, y=64, z=3, slots=27, item='minecraft:diamond', count=32)
            diagnostic = self.command('sort-explain')['state']
            (self.evidence / 'sort-diagnostics.json').write_text(json.dumps(diagnostic, indent=2) + '\n')
            probes = diagnostic['probes']
            require(any(c['reason'] == 'PROTECTED' for c in probes[0]['candidates']) and any(c['reason'] == 'EXCLUDED' for c in probes[0]['candidates']), 'Protection diagnostics missing')
            self.command('close')
            self.settle_test_menus()
            self.command('manager-inspection-hold', hold=False)
            activated = self.command('sort-start')['state']['sorterStorage']
            require(next(b for b in activated if b['x']==12)['excluded'] and next(b for b in activated if b['x']==15)['protected'],
                'Selecting the same stash name again lost its protection marks')
            time.sleep(3)
            blocked = self.command('state')['state']
            require(not blocked['sortingPermitted'] and blocked['attemptedClicks']==before_clicks
                and before_reserved=={x:self.nbt(x) for x in self.locations}, 'Sorter touched real reserved stock')
            status,_ = self.http('/api/v1/orders/'+reservation['order']['id']+'/cancel',{})
            require(status==200,'Cannot cancel fixture reservation')
            self.command('manager-sorting-hold', hold=False)
            self.wait(lambda: self.command('state')['state']['sortingPermitted'],
                      'Signed automation permit was not granted',timeout=30)
            queued_status, queued_reply = self.order()
            require(queued_status == 200, 'A new order was rejected during an active physical sorting permit')
            waiting_order = queued_reply['order']
            require(waiting_order['reservationDeferred'] and waiting_order['state'] == 'CREATED'
                    and waiting_order['status'] == 'pending' and not waiting_order.get('allocations'),
                    'Order accepted during sorting must remain unreserved until fresh physical claim')
            # Cancel this test-only waiting order so the sorter can complete the
            # existing deposit pass. A cancellation of an unreserved CREATED
            # order must not touch stock or require a bot-side physical ACK.
            cancelled_status, cancelled_reply = self.http(
                '/api/v1/orders/' + waiting_order['id'] + '/cancel', {})
            require(cancelled_status == 200 and cancelled_reply['order']['status'] == 'cancelled',
                    'Unreserved waiting order did not cancel immediately')
            self.ok('real signed Manager preserves reserved stock, queues new orders without allocations during sorting, and cancels the waiting request without a physical mutation')
            # Tier-only storage must empty the deposit, preserve every item's
            # exact metadata/count and leave protected/excluded inventory untouched.
            # Locations are chosen by the real tier planner, never by signs.
            self.wait(lambda: not snbt.items(self.nbt(0))
                and '[]' in self.rcon.command('data get entity ' + self.player + ' Inventory')
                and self.command('state')['state']['inventoryReady'],
                'Production tier sorter did not empty the mixed deposit',timeout=240)
            self.stop_sorter_safely()
            require(self.nbt(12) == excluded and self.nbt(15) == protected and self.nbt(30)==outbound,
                'Sorter moved excluded or protected kit stock')
            require(self.authoritative_totals() == baseline_totals,
                'Tier sorting changed authoritative recursive counts or item metadata')
            require(sum(self.count(x, 'oak_sign') for x in self.locations if x != 0) == 3,
                'The tier sorter did not store all ordinary signs as inventory items')
            self.ok('tier-only sorting empties deposit, preserves protected stock and stores signs as ordinary inventory')
            # The web Manager is still active and would immediately renew a new
            # autonomous sorting lease after the local sorter stops. Explicitly
            # disable only the isolated test's Manager physical sorting, leaving
            # the signed connection and real stock observations intact.
            self.command('manager-sorting-hold', hold=True)
            self.wait(lambda: not self.stock()['automation'],
                'Verified physical lease was not released after fixture stopped sorting', timeout=30)
            self.command('manager-unlink-test')
            # Manager may have issued its final index-open before deactivation.
            # A late server-side menu response must not be mistaken for this
            # fixture's protected barrel. Stop the scanner, explicitly close
            # any residual menu, and require a stable, safe inventory before
            # asserting the *real* protected QUICK_MOVE fence.
            baseline = self.command('state')['state']['attemptedClicks']
            quiet_since = None
            settle_deadline = time.monotonic() + 25
            while time.monotonic() < settle_deadline:
                observed = self.command('state')['state']
                require(observed['physicalJournal'] == 'READY'
                        and not observed['inventoryFailure']
                        and observed['attemptedClicks'] == baseline,
                        'Manager scanner stop left an unsafe inventory or replayed a click')
                if observed['containerId'] != 0:
                    self.command('close')
                    quiet_since = None
                else:
                    if quiet_since is None: quiet_since = time.monotonic()
                    if time.monotonic() - quiet_since >= 3:
                        break
                time.sleep(.25)
            else:
                raise RuntimeError('Manager scanner did not release its container menu safely')
            self.teleport(15.5,2); self.command('open',x=15,y=64,z=3,slots=27,item='minecraft:diamond',count=7)
            self.command('attempt-protected'); self.command('close')
            require(self.nbt(15)==protected,'Low-level guard changed protected stock')
            self.ok('actual production input mixin refuses a protected-container QUICK_MOVE before prediction or network send')
            # Tiers uses the actual planner and ignores signs. One empty box plus 26 full
            # 64-stacks fill the first container; the 16-stack and singleton use the next.
            for x in self.locations:
                if x not in [12, 15, 30]: self.rcon.command(f'data merge block {x} 64 3 {{Items:[]}}')
            self.setitem(0, 0, 'shulker_box', 1)
            for slot in range(1, 27): self.setitem(0, slot, 'dirt', 64)
            self.setitem(21, 0, 'egg', 8); self.setitem(21, 1, 'diamond_sword', 1)
            self.observe({0:('shulker_box',1),12:('diamond',5),15:('diamond',7),30:('diamond',9),21:('egg',8)})
            self.command('sort-mark', x=21,y=64,z=3,dropOff=True)
            second_baseline = self.authoritative_totals()
            self.teleport(0.5,2); self.command('sort-start', mode='Tiers')
            # Tier-only optimization can safely fill the existing empty
            # shulker with 26 full dirt stacks. The old test incorrectly
            # required those same stacks to remain loose in the barrel.
            self.wait(lambda: self.packed_count(3,'dirt')==26*64
                and self.count(3,'shulker_box')==1
                and self.count(0,'dirt')==0
                # Tier placement may migrate as the optimizer finishes. Only
                # require that overflow has left the marked drop-off; check
                # its eventual destinations after the verified idle stop.
                and self.count(21,'egg')==0 and self.count(21,'diamond_sword')==0
                and '[]' in self.rcon.command('data get entity '+self.player+' Inventory')
                and self.command('state')['state']['inventoryReady'],
                'Tier optimizer did not compact dirt into its shulker and separate the overflow tiers')
            self.stop_sorter_safely()
            # Tiers chooses the final destination dynamically. The previous
            # fixed x=6 assertion captured a transient state mid-sort; later
            # moves from x=6 to x=3 are legitimate and must finish safely.
            destinations = [x for x in self.locations if x not in (0, 12, 15, 21, 30)]
            require(sum(self.count(x, 'egg') for x in destinations) == 8
                    and sum(self.count(x, 'diamond_sword') for x in destinations) == 1,
                    'Tier planner did not store the overflow eggs and sword outside the drop-offs')
            require(self.authoritative_totals() == second_baseline,
                    'Tier optimization changed authoritative recursive item counts or metadata')
            require(self.nbt(12)==excluded and self.nbt(15)==protected and self.nbt(30)==outbound, 'Tier sorting touched protected stock')
            self.ok('actual Tiers optimizer groups 26 full dirt stacks inside one physical shulker and separates eggs and swords with exact conservation')
            # Every destination is physically full.
            for x in self.locations:
                if x in [12,15,30]: continue
                self.rcon.command(f'data merge block {x} 64 3 {{Items:[]}}')
                for slot in range(1 if x==0 else 27): self.setitem(x,slot,'spyglass',1)
            self.observe({**{x:('spyglass',1) for x in self.locations if x not in [12,15,30]},12:('diamond',5),15:('diamond',7),30:('diamond',9)})
            self.command('sort-mark',x=21,y=64,z=3,dropOff=False)
            full_before={x:self.nbt(x) for x in self.locations}
            self.teleport(0.5,2); self.command('sort-start',mode='Tiers')
            self.wait(lambda: 'Drop-off blocked: no unprotected destination has room' in (self.evidence/'client.log').read_text(),
                'Full stock has no explicit blocked alert',timeout=60)
            self.stop_sorter_safely()
            require(full_before=={x:self.nbt(x) for x in self.locations},'Full stock moved or lost items')
            self.ok('full tier storage produces an explicit blocked alert with unchanged server inventories')
            self.command('screenshot'); world.shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'sorting-world.png')
            report = {'result':'PASS','scope':'W-019 actual 26.3 production sorting','checks':self.checks,
                'manager_image':self.env['WESORT_IMAGE'],'manager_image_id':manager_image_id,
                'source_commit':world.subprocess.check_output(['git','rev-parse','HEAD'],cwd=world.ROOT,text=True).strip(),
                'dirty_worktree':bool(world.subprocess.check_output(['git','status','--porcelain'],cwd=world.ROOT,text=True).strip())}
            (self.evidence/'sorting-result.json').write_text(json.dumps(report,indent=2)+'\n')
            print('Evidence: '+str(self.evidence),flush=True)
        finally: self.close()

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--manager-image',required=True)
    SortingHarness(parser.parse_args().manager_image).execute()
