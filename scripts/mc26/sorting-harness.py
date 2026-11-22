#!/usr/bin/env python3
"""W-019: actual Signs/Tiers sorting, blocked capacity and protected sources in vanilla 26.3."""
import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('world', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
snbt_spec = importlib.util.spec_from_file_location('snbt', Path(__file__).with_name('server-snbt.py'))
snbt = importlib.util.module_from_spec(snbt_spec); snbt_spec.loader.exec_module(snbt)
require = world.require

class SortingHarness(world.Harness):
    locations = list(range(0, 33, 3))
    labels = {3: 'dias', 6: 'building blocks', 9: 'diamond swords', 12: 'dias', 15: 'dias', 18: 'misc', 24: 'strength', 27: 'egaps'}
    def fixture(self):
        super().fixture()
        self.rcon.command('fill -1 64 -4 35 66 4 minecraft:air')
        self.rcon.command('kill @e[type=minecraft:item]')
        for x in self.locations:
            self.rcon.command(f'setblock {x} 64 3 minecraft:barrel')
            if x in self.labels:
                self.rcon.command(f'setblock {x} 65 3 minecraft:oak_sign')
                self.rcon.command(f'data merge block {x} 65 3 {{front_text:{{messages:[{{text:"{self.labels[x]}"}},{{text:""}},{{text:""}},{{text:""}}]}}}}')
        self.setitem(0, 0, 'diamond', 32); self.setitem(0, 1, 'dirt', 16)
        self.setitem(0, 2, 'diamond_sword[minecraft:custom_name={text:"Do not rename"},minecraft:damage=3,minecraft:enchantments={"minecraft:unbreaking":1}]', 1)
        self.setitem(0, 3, 'enchanted_golden_apple', 4)
        self.setitem(0, 4, 'potion[minecraft:potion_contents={potion:"minecraft:strength"}]', 1)
        self.setitem(0, 5, 'spyglass', 1)
        for slot in range(27): self.setitem(3, slot, 'diamond', 60 if slot == 0 else 64)
        self.setitem(12, 0, 'diamond', 5); self.setitem(15, 0, 'diamond', 7); self.setitem(30, 0, 'diamond', 9)
        self.setitem(15, 1, 'shulker_box[minecraft:custom_name={text:"Reserved kit"},minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:8}}]]', 1)
        self.ok('mixed named/enchanted/damaged/potion/slang fixture, full diamond destination with one partial stack, excluded and protected kit storage')
    def setitem(self, x, slot, item, count):
        reply = self.rcon.command(f'item replace block {x} 64 3 container.{slot} with minecraft:{item} {count}')
        require('Replaced 1 slot' in reply, 'Rejected sorting fixture item: ' + reply)
    def nbt(self, x): return self.rcon.command(f'data get block {x} 64 3 Items')
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
            if first is not None: item, count = first.get(x, ('air', 0))
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
    def order(self):
        return self.http('/api/v1/orders', {'server':self.server_name,'stash':'e2e-fixture',
            'lines':[{'type':'item','key':'minecraft:diamond','count':1}]})
    def wait(self, predicate, message, timeout=180):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if predicate(): return
            time.sleep(0.5)
        raise RuntimeError(message)
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
            status, reservation = self.order(); require(status==200,'Could not create actual reservation')
            before_reserved = {x:self.nbt(x) for x in self.locations}
            before_clicks = self.command('state')['state']['attemptedClicks']
            self.teleport(0.5, 2); self.command('sort-config')
            self.command('open', x=0, y=64, z=3, slots=27, item='minecraft:diamond', count=32)
            labels = ['dias', 'building blocks', 'diamond swords', 'egaps', 'strength', 'Unbreaking I', 'Do not rename', 'misc']
            diagnostic = self.command('sort-explain', labels=labels)['state']
            (self.evidence / 'sort-diagnostics.json').write_text(json.dumps(diagnostic, indent=2) + '\n')
            probes = diagnostic['probes']
            require(next(p for p in probes if p['item'].startswith('minecraft:diamond_sword'))['scores']['Unbreaking I'] > 0, 'Actual enchantment category missing')
            require(next(p for p in probes if p['item'].startswith('minecraft:potion'))['scores']['strength'] > 0, 'Actual potion category missing')
            require(any(c['reason'] == 'PROTECTED' for c in probes[0]['candidates']) and any(c['reason'] == 'EXCLUDED' for c in probes[0]['candidates']), 'Protection diagnostics missing')
            self.command('close')
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
            self.command('sort-stop')
            require(self.nbt(12) == excluded and self.nbt(15) == protected and self.nbt(30)==outbound,
                'Sorter moved excluded or protected kit stock')
            require(self.authoritative_totals() == baseline_totals,
                'Tier sorting changed authoritative recursive counts or item metadata')
            self.ok('tier-only sorting empties deposit with exact server item conservation, preserving protected stock')
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
            self.teleport(0.5,2); self.command('sort-start', mode='Tiers')
            self.wait(lambda: self.count(3,'dirt')==26*64 and self.count(3,'shulker_box')==1
                and self.count(6,'egg')==8 and self.count(6,'diamond_sword')==1
                and '[]' in self.rcon.command('data get entity '+self.player+' Inventory') and self.command('state')['state']['inventoryReady'],
                'Actual tier planner did not separate the overflow tiers')
            self.command('sort-stop')
            require(self.nbt(12)==excluded and self.nbt(15)==protected and self.nbt(30)==outbound, 'Tier sorting touched protected stock')
            self.ok('actual Tiers planner fills 27-slot capacity in tier order and routes 16-stackables/singletons to the next container')
            # Every destination, including the unsigned overflow, is physically full.
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
            self.command('sort-stop')
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
