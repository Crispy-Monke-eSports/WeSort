#!/usr/bin/env python3
"""W-021 real repack/consolidation and bounded failures; all counts come from independent server SNBT."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import shutil
import time


def module(name, file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file)); value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
world=module('world','world-harness.py'); snbt=module('server_snbt','server-snbt.py'); network=module('network','inventory-harness.py'); require=world.require


class RepackHarness(world.Harness):
    locations=[0,3,6,9,12,15]
    def __init__(self,image,scenario):
        super().__init__(image);self.scenario=scenario
        self.proxy=network.Proxy(self.server_port) if scenario in ['lag','abort','full-stash','full-inventory'] else None
    def start_client(self):
        if self.proxy is None:return super().start_client()
        original=self.server_port;self.server_port=self.proxy.port
        try:return super().start_client()
        finally:self.server_port=original
    def close(self):
        try:super().close()
        finally:
            if self.proxy is not None:self.proxy.close()
    def fixture(self):
        super().fixture(); self.rcon.command('fill -1 64 -4 35 66 4 minecraft:air');self.rcon.command('kill @e[type=minecraft:item]')
        for x,label in [(0,'misc'),(3,'dias'),(6,'dirt'),(9,'misc'),(12,'kits'),(15,'diamond swords')]:
            self.rcon.command(f'setblock {x} 64 3 minecraft:barrel')
            self.rcon.command(f'setblock {x} 65 3 minecraft:oak_sign')
            # The kit container is explicitly protected; no global "kits" sign that requests all mixed boxes whole.
            if x==12:label='protected'
            self.rcon.command(f'data merge block {x} 65 3 {{front_text:{{messages:[{{text:"{label}"}},{{text:""}},{{text:""}},{{text:""}}]}}}}')
        self.rcon.command('item replace block 12 64 3 container.0 with minecraft:shulker_box[minecraft:custom_name={text:"Reserved kit"},minecraft:container=[{slot:26,item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":7,"minecraft:custom_name":{text:"Exact sword"}}}}]]')
        if self.scenario in ['compact','rounds']:
            for slot in range(27):self.rcon.command(f'item replace block 3 64 3 container.{slot} with minecraft:diamond 64')
            self.rcon.command('item replace block 9 64 3 container.0 with minecraft:shulker_box')
    def configure(self):
        for x in self.locations:
            self.teleport(x+.5,2)
            item,count=('shulker_box',1) if x==12 or self.scenario in ['compact','rounds'] and x==9 else ('diamond',64) if self.scenario in ['compact','rounds'] and x==3 else ('air',0)
            self.command('open',x=x,y=64,z=3,slots=27,item='minecraft:'+item,count=count);self.command('close')
        self.command('sort-mark',x=12,y=64,z=3,protected=True)
        self.command('sort-mark',x=0,y=64,z=3,dropOff=True)
        self.teleport(.5,0)
        self.rcon.command(f'clear {self.player}')
        # Deliberately omit the pickaxe in no-tool to prove hand-breaking works.
        if self.scenario!='no-tool':self.give(8,'diamond_pickaxe')
        if self.scenario in ['mixed','no-tool','full-stash','blocked','abort','lag','reserved','full-inventory','named']:
            self.give(0,'shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:32}},{slot:13,item:{id:"minecraft:dirt",count:16}},{slot:26,item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":4,"minecraft:custom_name":{text:"Keep exact"}}}}]]')
        elif self.scenario in ['partial','identical']:
            self.give(0,'shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:60}}]]');self.give(1,'diamond',10)
        elif self.scenario in ['bulk','no-empty']:
            if self.scenario=='bulk':self.give(0,'shulker_box')
            self.give(1,'diamond',64);self.give(2,'diamond',64)
            if self.scenario=='no-empty': self.give(0,'diamond',64)
        if self.scenario=='full-stash-initial':
            for x in [0,3,6,9,15]:
                for slot in range(27):self.rcon.command(f'item replace block {x} 64 3 container.{slot} with minecraft:bedrock 64')
            # Read actual capacity after the server-side change, without recapturing the protected source.
            for x in [0,3,6,9,15]:
                self.teleport(x+.5,2);self.command('open',x=x,y=64,z=3,slots=27,item='minecraft:bedrock',count=64);self.command('close')
            self.teleport(.5,0)
        if self.scenario not in ['compact','rounds']:
            self.teleport(.5,2)
            item,count=('diamond',64) if self.scenario=='no-empty' else ('shulker_box',1)
            self.command('open',x=0,y=64,z=3,slots=27,item='minecraft:'+item,count=count);self.command('close')
        if self.scenario=='identical':
            reply=self.rcon.command(f'item replace entity {self.player} hotbar.7 with minecraft:shulker_box[minecraft:container=[{{slot:0,item:{{id:"minecraft:diamond",count:64}}}},{{slot:1,item:{{id:"minecraft:diamond",count:6}}}}]]')
            require('Replaced' in reply,'Personal identical box fixture failed')
        if self.scenario=='named':
            self.give(20,'shulker_box[minecraft:custom_name={text:"Keep whole"},minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:2}},{slot:26,item:{id:"minecraft:dirt",count:3}}]]')
            self.teleport(.5,2);self.command('open',x=0,y=64,z=3,slots=27,item='minecraft:shulker_box',count=1);self.command('close')
        self.settings=dict(repack=True,compact=self.scenario in ['compact','rounds'],newBulkAt=2 if self.scenario in ['bulk','no-empty'] else 0,topUpAt=1,carrySlots='1-8,10-36',walk=True)
        if self.scenario=='rounds':self.settings['carrySlots']='1-8,10-20'
        self.command('sort-config',**self.settings)
        self.settle_test_menus()
        if self.scenario=='blocked':self.rcon.command(f'gamemode adventure {self.player}')
    def give(self,slot,item,count=1):
        if slot==8: reply=self.rcon.command(f'item replace entity {self.player} hotbar.8 with minecraft:{item} {count}')
        else: reply=self.rcon.command(f'item replace block 0 64 3 container.{slot} with minecraft:{item} {count}')
        require('Replaced' in reply,'Server inventory fixture rejected: '+reply)
    def cursor(self):
        reply=self.rcon.command(f'execute if items entity {self.player} player.cursor *')
        require('Test passed' in reply or 'Test failed' in reply,'Server cursor witness rejected: '+reply)
        return 'Test passed' in reply, reply
    def snapshots(self):
        result={**{str(x):self.rcon.command(f'data get block {x} 64 3 Items') for x in self.locations},'player':self.rcon.command(f'data get entity {self.player} Inventory')}
        position=self.command('state')['state'].get('repackPosition')
        if position:
            pos=' '.join(map(str,position))
            if 'Test passed' in self.rcon.command(f'execute if block {pos} minecraft:shulker_box'):
                result['standing']=self.rcon.command(f'data get block {pos} Items')
        if 'Test passed' in self.rcon.command('execute if entity @e[type=minecraft:item]'):
            result['drop']=self.rcon.command('data get entity @e[type=minecraft:item,limit=1] Item')
        return result
    def totals(self,snapshots):
        totals={}
        for place,text in snapshots.items():
            stacks=snbt.items(text)
            if place=='drop':stacks=[stacks]
            for key,count in snbt.totals(stacks).items():totals[key]=totals.get(key,0)+count
            if place=='standing':totals['minecraft:shulker_box|{}']=totals.get('minecraft:shulker_box|{}',0)+1
        return totals
    def wait(self,predicate,message,timeout=150):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            state=self.command('state')['state']
            (self.evidence/'latest-state.json').write_text(json.dumps(state,indent=2)+'\n')
            if predicate(state):return state
            if state['inventoryFailure'] and self.scenario not in ['abort','full-stash','full-inventory']:raise RuntimeError('Physical readback blocked: '+state['inventoryFailure'])
            phase=(state.get('repackProgress') or {}).get('phase')
            if phase=='NEEDS_RECONCILIATION' and self.scenario not in ['abort','full-stash','full-inventory']:
                raise RuntimeError('Repack fenced before verified completion: '+str((state.get('repackProgress') or {}).get('reason','')))
            time.sleep(.25)
        raise RuntimeError(message)
    def execute(self):
        try:
            self.start_server();self.fixture()
            if self.scenario=='reserved':self.start_manager()
            self.start_client();self.configure()
            if self.scenario=='reserved':
                self.command('publish',url=f'http://127.0.0.1:{self.manager_port}',key=self.key,
                             holdPhysicalSorting=True)
                until=time.monotonic()+90
                while time.monotonic()<until:
                    code,stock=self.http('/api/v1/stock')
                    if code==200 and any(i['id']=='minecraft:diamond' and i['available']>=1 for i in stock['items']):break
                    time.sleep(.25)
                else:raise RuntimeError('Reserved repack fixture never acquired fresh physical stock')
                server=self.command('state')['state']['server']
                code,_=self.http('/api/v1/orders',{'server':server,'stash':'e2e-fixture','lines':[{'type':'item','key':'minecraft:diamond','count':1}]})
                require(code==200,'Could not reserve actual shulker contents before repack')
            before=self.snapshots();(self.evidence/'server-before.json').write_text(json.dumps(before,indent=2)+'\n')
            if self.scenario=='lag':self.proxy.delay_up=self.proxy.delay_down=.15
            if self.scenario in ['full-stash','full-inventory']:self.proxy.delay_down=.1
            self.command('sort-start',**self.settings)
            if self.scenario=='abort':
                self.wait(lambda s:s.get('repackProgress',{}).get('phase') in ['OPEN','TRANSFER'],'No placed shulker to interrupt')
                self.proxy.hold_down=True
            injected=0;incoming_counts={}
            if self.scenario=='reserved':
                initial=self.command('state')['state'];time.sleep(5);state=self.command('state')['state']
                require(not state['sortingPermitted'] and state['attemptedClicks']==initial['attemptedClicks'],'Reserved stock allowed a repack click')
                self.command('sort-stop');after=self.snapshots();require(before==after,'Reservation fence allowed a physical repack mutation')
                (self.evidence/'server-after.json').write_text(json.dumps(after,indent=2)+'\n')
                self.ok('signed Docker Manager reservation blocks real repack before placement or item prediction; authoritative snapshots unchanged')
                result={'result':'PASS','scenario':self.scenario,'server_sha1':world.SERVER_SHA1,'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=world.ROOT,text=True).strip(),'dirty_worktree':True,'manager_image':self.env['WESORT_IMAGE'],'checks':self.checks,'operation':{'phase':'RESERVATION_BLOCKED'}}
                (self.evidence/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('Evidence: '+str(self.evidence),flush=True);return
            if self.scenario=='full-stash':
                self.wait(lambda s:s.get('repackProgress',{}).get('phase') in ['OPEN','TRANSFER','VERIFY','BREAK'],'No verified physical step for full-storage fault injection')
                self.proxy.hold_up=True;time.sleep(.2)
                print('FAULT: game uplink held for capacity injection',flush=True)
                for x in [0,3,6,9,15]:
                    require(not snbt.items(self.rcon.command(f'data get block {x} 64 3 Items')),'Fault injector would overwrite existing items')
                    kind='diamond' if x==3 else 'dirt' if x==6 else 'diamond_sword' if x==15 else 'bedrock'
                    quantity=1 if kind=='diamond_sword' else 64
                    entries=','.join('{Slot:'+str(slot)+'b,id:"minecraft:'+kind+'",count:'+str(quantity)+'}' for slot in range(27))
                    reply=self.rcon.command(f'data modify block {x} 64 3 Items set value [{entries}]')
                    require('Modified' in reply,'Capacity fault injection rejected: '+reply); injected+=27*quantity
                    key='minecraft:'+kind+'|{}';incoming_counts[key]=incoming_counts.get(key,0)+27*quantity
                self.proxy.hold_up=False
            if self.scenario=='full-inventory':
                self.wait(lambda s:s.get('repackProgress',{}).get('phase') in ['OPEN','TRANSFER'],'No placed shulker for inventory-capacity fault')
                self.proxy.hold_up=True;time.sleep(.2)
                print('FAULT: game uplink held for capacity injection',flush=True)
                occupied={s['Slot'] for s in snbt.items(self.rcon.command(f'data get entity {self.player} Inventory'))}
                for slot in range(36):
                    if slot in occupied:continue
                    part=f'hotbar.{slot}' if slot<9 else f'inventory.{slot-9}'
                    reply=self.rcon.command(f'item replace entity {self.player} {part} with minecraft:bedrock 64')
                    require('Replaced' in reply,'Full inventory injector failed');injected+=64
                    incoming_counts['minecraft:bedrock|{}']=incoming_counts.get('minecraft:bedrock|{}',0)+64
                self.proxy.hold_up=False
            # Shulkers are breakable by hand: no-tool is a success path, not a failure.
            failure=self.scenario in ['no-empty','blocked']
            terminal='NEEDS_RECONCILIATION' if self.scenario in ['full-stash','abort','full-inventory'] else 'FAILED' if failure else 'COMPLETED'
            state=self.wait(lambda s:s.get('repackProgress',{}).get('phase')==terminal,'Repack phase did not reach verified completion or explicit precondition failure')
            if self.scenario=='abort':
                self.proxy.hold_down=False; clicks=state['attemptedClicks'];time.sleep(2)
                require(self.command('state')['state']['attemptedClicks']==clicks,'Uncertain repack retried clicks after link recovery')
            if self.scenario in ['full-stash','abort','full-inventory']:
                # The server's menu cursor is not part of entity Inventory NBT. After automation is fenced,
                # explicitly close the test menu and verify the actual cursor is empty before total accounting.
                # This is a recorded manual test recovery action, never an automatic retry by the product.
                _,cursor=self.cursor()
                (self.evidence/'cursor-before-manual-recovery.txt').write_text(cursor+'\n')
                clicks=self.command('state')['state']['attemptedClicks']
                self.command('close')
                until=time.monotonic()+10
                while self.cursor()[0]:
                    require(time.monotonic()<until,'Manual test close did not clear the actual server cursor');time.sleep(.1)
                require(self.command('state')['state']['attemptedClicks']==clicks,'Fenced repack retried an inventory click during manual recovery')
            self.command('sort-stop')
            after=self.snapshots();(self.evidence/'server-after.json').write_text(json.dumps(after,indent=2)+'\n')
            expected_totals=self.totals(before)
            for key,amount in incoming_counts.items():expected_totals[key]=expected_totals.get(key,0)+amount
            require(expected_totals==self.totals(after),'Authoritative recursive server item/component totals changed')
            require(before['12']==after['12'],'Protected named kit changed')
            if failure or self.scenario in ['full-stash','abort','full-inventory']:
                require('reason' in state['repackProgress'] and state['repackProgress']['reason'],'Blocked operation has no diagnostic')
                if failure:require(not any(e['phase'] in ['PLACE','TRANSFER','BREAK'] for e in state['repackProgress']['events']), 'Precondition failure reached physical work')
                if self.scenario=='blocked':
                    original=next(s for text in before.values() for s in snbt.items(text) if s['id']=='minecraft:shulker_box' and not s.get('components',{}).get('minecraft:custom_name'))
                    candidates=[s for text in after.values() for s in snbt.items(text)]
                    require(any(s['id']==original['id'] and s.get('components',{})==original.get('components',{}) for s in candidates),'Blocked shulker contents were changed')
            else:
                phases={event['phase'] for event in state['repackProgress']['events']}
                require({'SELECT','PLACE','OPEN','TRANSFER','VERIFY','BREAK','PICKUP','STORE','COMPLETED'}<=phases,'Physical phase evidence incomplete: '+str(phases))
                stored=[stack for x in self.locations for stack in snbt.items(after[str(x)]) if stack['id']=='minecraft:shulker_box' and not stack.get('components',{}).get('minecraft:custom_name')]
                require(stored,'Repacked box was not stored in server inventory')
                if self.scenario=='rounds':require(sum(e['phase']=='TRANSFER' for e in state['repackProgress']['events'])>=2,'Compactor did not execute multiple verified rounds')
                expected=70 if self.scenario in ['partial','identical'] else 128 if self.scenario=='bulk' else 1728 if self.scenario in ['compact','rounds'] else 32
                require(any(snbt.totals([stack]).get('minecraft:diamond|{}')==expected for stack in stored),'Stored box has incorrect verified inner amount')
                require('Test failed' in self.rcon.command('execute if entity @e[type=minecraft:item]'),'Operation left an item drop behind')
            if self.scenario=='named':
                original=next(s for text in before.values() for s in snbt.items(text) if s.get('components',{}).get('minecraft:custom_name')=='Keep whole')
                require(any(s.get('components',{})==original['components'] for text in after.values() for s in snbt.items(text)),'Unprotected named shulker was repacked or changed')
                self.command('screenshot');shutil.copyfile(self.game_dir/'screenshots/fixture.png',self.evidence/'fixture.png')
            self.ok(self.scenario+': actual server snapshots conserve full recursive metadata/counts; protected kit unchanged; '+state['repackProgress']['phase'])
            result={'result':'PASS','scenario':self.scenario,'server_sha1':world.SERVER_SHA1,'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=world.ROOT,text=True).strip(),'dirty_worktree':True,'checks':self.checks,'operation':state['repackProgress'],'fault_injected':incoming_counts,'manual_test_recovery':self.scenario in ['full-stash','abort','full-inventory']}
            (self.evidence/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('Evidence: '+str(self.evidence),flush=True)
        finally:self.close()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manager-image',default='ghcr.io/crispy-monke-esports/wesort-manager@sha256:5d81b3a84a8089c00daa5541d2dab57ed8779aeb6a0d5b43a41c21c726f0f55f');p.add_argument('--scenario',choices=['mixed','partial','bulk','compact','no-tool','full-stash','no-empty','lag','abort','reserved','blocked','full-inventory','named','identical','rounds'],default='mixed')
    args=p.parse_args();RepackHarness(args.manager_image,args.scenario).execute()
