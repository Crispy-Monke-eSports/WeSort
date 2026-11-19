#!/usr/bin/env python3
"""W-022 real exact kit templates, partial layouts, shortages, interruption and signed guild sharing."""
import argparse
import importlib.util
import json
import os
import signal
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse

spec=importlib.util.spec_from_file_location('repack',Path(__file__).with_name('repack-harness.py'))
repack=importlib.util.module_from_spec(spec);spec.loader.exec_module(repack)
world=repack.world;snbt=repack.snbt;require=world.require


class KitHarness(repack.RepackHarness):
    locations=[0,3,6,9,12,15,18]
    name='Raid alpha'
    sword='diamond_sword[minecraft:damage=7,minecraft:repair_cost=3,minecraft:enchantments={"minecraft:unbreaking":2},minecraft:custom_name={text:"Raid sword",color:"gold"},minecraft:custom_data={typed:1b}]'
    potion='potion[minecraft:potion_contents={potion:"minecraft:strength"},minecraft:custom_name={text:"Kit potion"}]'
    bundle='bundle[minecraft:bundle_contents=[{id:"minecraft:iron_ingot",count:8}]]'
    inner='[{slot:0,item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":7,"minecraft:repair_cost":3,"minecraft:enchantments":{"minecraft:unbreaking":2},"minecraft:custom_name":{text:"Raid sword",color:"gold"},"minecraft:custom_data":{typed:1b}}}},{slot:1,item:{id:"minecraft:diamond",count:32}},{slot:13,item:{id:"minecraft:diamond",count:16}},{slot:25,item:{id:"minecraft:bundle",count:1,components:{"minecraft:bundle_contents":[{id:"minecraft:iron_ingot",count:8}]}}},{slot:26,item:{id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:strength"},"minecraft:custom_name":{text:"Kit potion"}}}}]'
    def fixture(self):
        world.Harness.fixture(self);self.rcon.command('fill -1 64 -4 35 66 4 minecraft:air');self.rcon.command('kill @e[type=minecraft:item]')
        for x in self.locations:
            self.rcon.command(f'setblock {x} 64 3 minecraft:barrel')
            label=self.name if x==12 else 'empty shulkers' if x==0 else 'misc'
            self.rcon.command(f'setblock {x} 65 3 minecraft:oak_sign')
            self.rcon.command(f'data merge block {x} 65 3 {{front_text:{{messages:[{{text:"{label}"}},{{text:""}},{{text:""}},{{text:""}}]}}}}')
        self.setitem(18,0,'shulker_box[minecraft:custom_name={text:"Reserved exact kit"},minecraft:container='+self.inner+']')
    def setitem(self,x,slot,item,count=1):
        reply=self.rcon.command(f'item replace block {x} 64 3 container.{slot} with minecraft:{item} {count}')
        require('Replaced' in reply,'Kit source fixture rejected: '+reply)
    def playeritem(self,slot,item,count=1):
        part=f'hotbar.{slot}' if slot<9 else f'inventory.{slot-9}'
        reply=self.rcon.command(f'item replace entity {self.player} {part} with minecraft:{item} {count}')
        require('Replaced' in reply,'Template inventory fixture rejected: '+reply)
    def info(self):return self.command('kit-info',name=self.name)['state']
    def setup_template(self):
        self.command('publish',url=f'http://127.0.0.1:{self.manager_port}',key=self.key)
        self.server_name=self.command('state')['state']['server']
        query=urllib.parse.quote(self.server_name,safe='')
        until=time.monotonic()+60
        while time.monotonic()<until:
            code,editor=self.http('/api/v1/kits/editor?server='+query)
            if code==200 and len(editor.get('variants',[]))>=5 and editor.get('destinations'):break
            time.sleep(.25)
        require(code==200 and editor.get('variants'),'No native observed variants in Manager editor')
        (self.evidence/'manager-editor.json').write_text(json.dumps(editor,indent=2)+'\n')
        def variant(item):
            found=[v for v in editor['variants'] if v['id']=='minecraft:'+item]
            if item=='diamond_sword':
                found=[v for v in found if json.loads(v['components'])['value'].get('minecraft:damage',{}).get('value')=='7' and json.loads(v['components'])['value'].get('minecraft:custom_data',{}).get('value',{}).get('typed',{}).get('type')==1]
            require(len(found)==1,'Exact observed variant ambiguous: '+item)
            return found[0]['fingerprint']
        layout=[{'slot':slot,'variant':variant(item),'count':count} for slot,item,count in [(0,'diamond_sword',1),(1,'diamond',32),(13,'diamond',16),(25,'bundle',1),(26,'potion',1)]]
        code,catalog=self.http('/api/v1/kits',{'action':'publish','server':self.server_name,'name':self.name,'layout':layout,'expectedRevision':0})
        require(code==200,'Manager web creation failed: '+str(catalog))
        entry=catalog['templates'][0];self.template=entry['template'];self.template_revision=entry['revision'];self.layout=layout
        (self.evidence/'template.json').write_text(json.dumps(self.template,indent=2)+'\n')
        target=[d for d in editor['destinations'] if d['parts']==[[12,64,3]]]
        require(len(target)==1,'Manager has no fresh physical kit destination')
        self.destination=target[0]['id']
        # A synthetic obsolete file only inside this fresh test installation. No existing user data is accessed.
        old=self.game_dir/'meteor-client/wesort/kits/ignored-fixture.nbt';old.parent.mkdir(exist_ok=True);old.write_bytes(b'old-template-fixture-not-imported')
        self.old_fixture=old;self.old_bytes=old.read_bytes();self.old_mtime=old.stat().st_mtime_ns
        self.ok('Manager-created versioned exact template from real observed components; no client template administration')
    def assign(self):
        code,job=self.http('/api/v1/kits/jobs',{'server':self.server_name,'stash':'e2e-fixture','destination':self.destination,'name':self.name,'fingerprint':self.template['fingerprint'],'revision':self.template_revision,'workspace':{'dimension':'minecraft:overworld','x':15,'y':64,'z':0}})
        require(code==200,'Manager job creation failed: '+str(job));self.job_id=job['id']
        self.wait(lambda s:bool(self.job().get('agent')) and self.info().get('exists',False),'Assigned exact template did not reach Fabric')
        require(self.info()['template']==self.template,'Signed Manager assignment changed native typed components/layout')
    def job(self):
        code,body=self.http('/api/v1/kits/jobs?server='+urllib.parse.quote(self.server_name,safe=''));require(code==200,'Manager job list failed')
        return next(j for j in body['jobs'] if j['id']==self.job_id)
    def setup_sources(self):
        self.rcon.command(f'clear {self.player}');self.playeritem(8,'diamond_pickaxe')
        partial=self.scenario=='partial'
        if partial:
            # Same exact metadata in deliberately wrong slots; the packer must repair the whole 27-slot layout.
            inner=self.inner.replace('slot:0,item:{id:"minecraft:diamond_sword"','slot:26,item:{id:"minecraft:diamond_sword"').split('},{slot:1,')[0]+'},{slot:0,item:{id:"minecraft:diamond",count:16}}]'
            self.setitem(12,0,'shulker_box[minecraft:container='+inner+']')
        else:self.setitem(0,0,'shulker_box')
        self.setitem(3,0,'diamond',32 if partial else 64)
        if not partial and self.scenario!='variant':self.setitem(6,0,self.sword)
        self.setitem(6,1,self.sword.replace('damage=7','damage=8'))
        self.setitem(6,2,self.sword.replace('typed:1b','typed:1'))
        self.setitem(9,0,self.potion);self.setitem(15,0,self.bundle)
        if self.scenario in ['repeat','repeat-version']:
            self.setitem(0,1,'shulker_box');self.setitem(3,1,'diamond',33 if self.scenario=='repeat-version' else 32);self.setitem(6,3,self.sword);self.setitem(9,1,self.potion);self.setitem(15,1,self.bundle)
        for x in self.locations:
            self.teleport(x+.5,2)
            item,count=('shulker_box',1) if x in [0,18] and not partial or x in [12,18] and partial else ('diamond',32 if partial else 64) if x==3 else ('diamond_sword',1) if x==6 and not partial and self.scenario!='variant' else ('potion',1) if x==9 else ('bundle',1) if x==15 else ('air',0)
            self.command('open',x=x,y=64,z=3,slots=27,item='minecraft:'+item,count=count);self.command('close')
        self.command('sort-mark',x=18,y=64,z=3,protected=True)
        self.teleport(18.5,2);self.command('open',x=18,y=64,z=3,slots=27,item='minecraft:shulker_box',count=1);self.command('close')
        # Keep interactions well inside actual server reach. The partial-kit
        # source at x=12 is behind adjacent barrels; a 4.5-block center-distance
        # check can stop walking too early and get an unaccepted click.
        self.settings=dict(kits=False,repack=False,compact=False,newBulkAt=0,carrySlots='1-8,10-36',walk=True,reach=3.0)
        self.command('sort-config',**self.settings)
    def completed(self):
        job=self.job();require(job['state']!='NEEDS_RECONCILIATION','Kit execution paused: '+str(self.info()))
        return job['state']=='COMPLETED'
    def interrupt_job(self):
        state=self.wait(lambda s:s['shulkerPhase']=='Work','No actual physical kit operation to interrupt')
        standing=state.get('repackPosition')
        if self.scenario=='version':
            edited=[dict(row) for row in self.layout];edited[-1]['count']=1;edited[2]['count']=15
            code,body=self.http('/api/v1/kits',{'action':'publish','server':self.server_name,'name':self.name,'layout':edited,'expectedRevision':self.template_revision})
            require(code==200,'Manager version edit failed: '+str(body))
            require(body['templates'][0]['template']['fingerprint']!=self.template['fingerprint'],'Version edit did not change exact layout')
            time.sleep(6)
        elif self.scenario=='offline':
            self.run(self.compose+['stop','manager']);time.sleep(7)
            first=self.command('state')['state'];time.sleep(2);require(self.command('state')['state']['attemptedClicks']==first['attemptedClicks'],'Manager offline permitted physical retries after lease expiry')
            self.run(self.compose+['start','manager'])
            until=time.monotonic()+30
            while time.monotonic()<until:
                try:
                    if self.http('/health/ready')[0]==200:break
                except OSError:pass
                time.sleep(.2)
        else:
            os.killpg(self.client.pid,signal.SIGTERM)
            try:self.client.wait(timeout=15)
            except subprocess.TimeoutExpired:os.killpg(self.client.pid,signal.SIGKILL);self.client.wait(timeout=5)
            (self.evidence/'client.log').rename(self.evidence/'client-before-restart.log');time.sleep(2);self.start_client()
            require(self.command('state')['state']['attemptedClicks']==0,'Agent replayed physical inputs on restart')
        until=time.monotonic()+20
        while self.job()['state']!='NEEDS_RECONCILIATION':require(time.monotonic()<until,'Interrupted job was not fenced in Manager');time.sleep(.2)
        require(self.job()['fingerprint']==self.template['fingerprint'],'Interrupted job silently substituted another template')
        first=self.command('state')['state']['attemptedClicks'];time.sleep(2);require(self.command('state')['state']['attemptedClicks']==first,'Interrupted kit job automatically retried')
        (self.evidence/'manager-receipt.json').write_text(json.dumps(self.job(),indent=2)+'\n')
        # Explicit manual test recovery only, never an automatic product retry.
        self.command('close');until=time.monotonic()+10
        while self.cursor()[0]:require(time.monotonic()<until,'Manual cursor close failed');time.sleep(.1)
        self.interrupted_standing=standing

    def execute(self):
        try:
            self.start_server();self.fixture()
            self.start_manager();self.start_client();self.setup_sources();self.setup_template()
            if self.scenario=='reserved':
                server=self.command('state')['state']['server']
                until=time.monotonic()+90
                while time.monotonic()<until:
                    code,stock=self.http('/api/v1/stock')
                    if code==200 and any(i['id']=='minecraft:diamond' and i['available']>=1 for i in stock['items']):break
                    time.sleep(.25)
                code,_=self.http('/api/v1/orders',{'server':server,'stash':'e2e-fixture','lines':[{'type':'item','key':'minecraft:diamond','count':1}]});require(code==200,'Kit reservation fixture failed')
            before=self.snapshots();(self.evidence/'server-before.json').write_text(json.dumps(before,indent=2)+'\n');self.assign()
            supply=self.info();(self.evidence/'supply-before.json').write_text(json.dumps(supply,indent=2)+'\n')
            # The assigned Manager job drives the existing Fabric algorithms without activating the sorter module.
            if self.scenario in ['variant','reserved']:
                clicks=self.command('state')['state']['attemptedClicks'];time.sleep(4);state=self.command('state')['state']
                if self.scenario=='variant':
                    supply=self.info();require(supply['possible']==0 and 'Missing' in supply['shortage'] and 'exact components' in supply['shortage'],'Wrong damage/NBT type was treated as available material');require(self.job()['state']=='NEEDS_RECONCILIATION','Missing exact variant did not reach Manager')
                if self.scenario=='reserved':require(not state['sortingPermitted'] and state['attemptedClicks']==clicks,'Reserved kit material was mutated')
                require(not snbt.items(self.rcon.command('data get block 12 64 3 Items')),'A missing/reserved variant produced a kit')
            elif self.scenario in ['offline','version','restart']:
                self.interrupt_job()
            elif self.scenario=='abort':
                self.wait(lambda s:s['shulkerPhase'] in ['Open','Work'],'No actual kit packing step to interrupt');self.proxy.hold_down=True
                state=self.wait(lambda s:bool(s['inventoryFailure']) or s.get('repackProgress',{}).get('phase')=='NEEDS_RECONCILIATION','Interrupted kit packing was not fenced',timeout=35)
                self.proxy.hold_down=False;clicks=state['attemptedClicks'];time.sleep(2);require(self.command('state')['state']['attemptedClicks']==clicks,'Kit packing retried physical clicks on link recovery')
                _,cursor=self.cursor();(self.evidence/'cursor-before-manual-recovery.txt').write_text(cursor+'\n')
                self.command('close');end=time.monotonic()+10
                while self.cursor()[0]:require(time.monotonic()<end,'Server cursor did not clear after explicit manual test close');time.sleep(.1)
            else:
                self.wait(lambda s:self.completed(),'No independently verified Manager kit completion',timeout=180)
                if self.scenario in ['repeat','repeat-version']:
                    first_box=snbt.items(self.rcon.command('data get block 12 64 3 Items'))[0]
                    (self.evidence/'manager-first-receipt.json').write_text(json.dumps(self.job(),indent=2)+'\n')
                    time.sleep(2);code,_=self.http('/api/v1/rescan',{'server':self.server_name,'stash':'e2e-fixture'});require(code==200,'Manager rescan between kit jobs failed')
                    until=time.monotonic()+90
                    while True:
                        code,editor=self.http('/api/v1/kits/editor?server='+urllib.parse.quote(self.server_name,safe=''))
                        if any(d['id']==self.destination for d in editor.get('destinations',[])):break
                        require(time.monotonic()<until,'No fresh destination for second kit job');time.sleep(.25)
                    if self.scenario=='repeat-version':
                        self.layout=[dict(row) for row in self.layout];self.layout[2]['count']=17
                        code,catalog=self.http('/api/v1/kits',{'action':'publish','server':self.server_name,'name':self.name,'layout':self.layout,'expectedRevision':self.template_revision})
                        require(code==200,'Second-version Manager edit failed');entry=catalog['templates'][0];self.template=entry['template'];self.template_revision=entry['revision']
                        (self.evidence/'template-second.json').write_text(json.dumps(self.template,indent=2)+'\n')
                    self.assign();self.wait(lambda s:self.completed(),'Second identical-version kit was not stored',timeout=180)
                    require(self.job()['baseline']==(0 if self.scenario=='repeat-version' else 1),'Second job did not pin the exact version baseline')
                    require(first_box in snbt.items(self.rcon.command('data get block 12 64 3 Items')),'A previously finished kit was consumed as a new-version partial')
                (self.evidence/'manager-receipt.json').write_text(json.dumps(self.job(),indent=2)+'\n')
            self.command('sort-stop');after=self.snapshots();
            if getattr(self,'interrupted_standing',None) and 'standing' not in after:
                pos=' '.join(map(str,self.interrupted_standing))
                if 'Test passed' in self.rcon.command(f'execute if block {pos} minecraft:shulker_box'):after['standing']=self.rcon.command(f'data get block {pos} Items')
            (self.evidence/'server-after.json').write_text(json.dumps(after,indent=2)+'\n')
            require(self.totals(before)==self.totals(after),'Kit pipeline changed exact recursive server item/component totals')
            require(before['18']==after['18'],'Protected finished kit was consumed or changed')
            if self.scenario in ['pack','partial','repeat','repeat-version']:
                self.teleport(12.5,2);self.command('open',x=12,y=64,z=3,slots=27,item='minecraft:shulker_box',count=1)
                comparison=self.command('kit-compare',name=self.name,slot=1 if self.scenario=='repeat-version' else 0)['state'];require(comparison['matches'] and comparison['finished'],'Stored physical box differs from exact 27-slot template')
                require(comparison['actual']['fingerprint']==self.template['fingerprint'],'Physical contents fingerprint differs from persisted template')
                (self.evidence/'physical-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n');self.command('screenshot');shutil.copyfile(self.game_dir/'screenshots/fixture.png',self.evidence/'fixture.png');self.command('close')
            require(self.old_fixture.read_bytes()==self.old_bytes and self.old_fixture.stat().st_mtime_ns==self.old_mtime,'Older synthetic kit file was read/modified/imported')
            self.ok(self.scenario+': Manager-owned signed version and exact native codec roundtrip, server-authoritative 27-slot/material behavior, protected kit unchanged and no variant substitution')
            result={'result':'PASS','scenario':self.scenario,'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=world.ROOT,text=True).strip(),'dirty_worktree':True,'server_sha1':world.SERVER_SHA1,'checks':self.checks,'manual_test_recovery':self.scenario in ['abort','offline','version','restart']}
            (self.evidence/'kit-result.json').write_text(json.dumps(result,indent=2)+'\n');print('Evidence: '+str(self.evidence),flush=True)
        finally:self.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--manager-image',default='wesort-manager:kit-tests');parser.add_argument('--scenario',choices=['pack','partial','repeat','repeat-version','variant','reserved','abort','offline','version','restart'],default='pack')
    args=parser.parse_args();KitHarness(args.manager_image,args.scenario).execute()
