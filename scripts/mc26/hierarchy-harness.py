#!/usr/bin/env python3
"""W-020: native 26.3 component/slot hierarchy, independent server snapshots and signed Docker persistence."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import uuid
import importlib.util
import json
import secrets
from http.cookiejar import CookieJar
import urllib.request
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse

spec = importlib.util.spec_from_file_location('stock_harness', Path(__file__).with_name('stock-harness.py'))
stock = importlib.util.module_from_spec(spec); spec.loader.exec_module(stock)
world = stock.world
require = world.require


class HierarchyHarness(stock.StockHarness):
    reservations = False
    guild = False
    order_lifecycle = False
    approval = False
    remote_settings = False
    scoped_agent = False

    def fixture(self):
        world.Harness.fixture(self)
        if self.remote_settings:
            self.rcon.command('setblock 6 64 -3 minecraft:chest')
        box = 'minecraft:shulker_box[minecraft:custom_name={text:"Hierarchy box"},minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:32}},{slot:13,item:{id:"minecraft:bundle",count:1,components:{"minecraft:bundle_contents":[{id:"minecraft:iron_ingot",count:8}]}}},{slot:26,item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":7,"minecraft:custom_name":{text:"Exact sword"},"minecraft:enchantments":{"minecraft:unbreaking":2}}}}]]'
        for command in [
            f'item replace block 0 64 -3 container.0 with {box} 1',
            f'item replace block 0 64 -3 container.20 with {box} 1',
            f'item replace block 6 64 -3 container.0 with {box} 1',
        ]:
            reply = self.rcon.command(command)
            require('replaced' in reply.lower(), 'Native hierarchy fixture rejected: ' + reply)
        # Keep this hierarchy scenario bounded; the general world/scanning suite owns the ten-chest matrix.
        self.rcon.command('fill 0 64 3 29 64 3 minecraft:air')
        self.ok('three identical named shulkers in distinct slots/containers with nested bundle and exact named enchanted damaged sword')

    def read(self, x, item='minecraft:shulker_box', count=1):
        self.teleport(x + 0.5, -1.5)
        self.command('open', x=x, y=64, z=-3, slots=27, item=item, count=count)
        self.command('close')

    def detail(self, source):
        status, detail = self.http('/api/v1/stock/source?id=' + urllib.parse.quote(source['id']))
        require(status == 200, 'Physical detail unavailable')
        return detail

    def verify_reservations(self, row, before):
        positions = {slot['path']: slot for slot in row['snapshot']['positions']}
        orders = [
            {'type': 'shulker', 'key': positions['0']['group'], 'count': 3},
            {'type': 'item', 'key': positions['0/0']['key'], 'count': 96},
        ]

        def place(line):
            return self.http('/api/v1/orders', {'server': self.server_name, 'stash': 'e2e-fixture', 'lines': [line]})

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(place, orders))
        require(sorted(status for status, _ in results) == [200, 409], 'Physical order race did not exclude the conflicting allocation')
        winner = next(i for i, (status, _) in enumerate(results) if status == 200)
        order = results[winner][1]['order']
        detail = self.detail(row)
        require(len(order['allocations']) == 3 and len(detail['allocations']) == 2, 'Physical source did not report its exact order allocations')
        remaining_ref = orders[1 - winner]['type'] + ':' + orders[1 - winner]['key']
        require(detail['available'].get(remaining_ref, 0) == 0, 'Reserved shulker roots still advertise competing items')
        agent = stock.signed.Client(f'http://127.0.0.1:{self.manager_port}', self.key)
        code, _ = agent.post('/api/v1/automation', {
            'server': self.server_name, 'agent': str(uuid.uuid4()), 'action': 'acquire'
        })
        require(code == 409, 'Sorting or kit execution could acquire a lease over reserved stock')
        code, _ = self.http('/api/v1/orders/' + order['id'] + '/cancel', {})
        require(code == 200 and self.detail(row)['available'].get(remaining_ref, 0) > 0,
                'Pending cancellation did not restore the physical availability')
        code, replacement = place(orders[1 - winner])
        require(code == 200, 'Cancellation did not make the separate physical request orderable')
        self.http('/api/v1/orders/' + replacement['order']['id'] + '/cancel', {})
        require(before == {str(x): self.rcon.command(f'data get block {x} 64 -3 Items') for x in [0, 3, 6]},
                'The reservation or cancellation changed real world inventory')
        self.ok('same live 26.3 shulker roots cannot be reserved as both whole boxes and child stock; sorting lease refused and cancellation restored availability')

    def verify_guild(self):
        owner = self.browser
        password = secrets.token_urlsafe(32)
        users = {}
        identities = {}
        status, guild = self.http('/api/v1/guild')
        require(status == 200, 'Owner could not read the guild profile')
        status, guild = self.http('/api/v1/guild', {
            'name': 'Fixture guild', 'description': 'One shared warehouse',
            'expectedRevision': guild['revision'],
        })
        require(status == 200, 'Owner could not edit the guild profile')
        try:
            for name, role in [('Builder', 'member'), ('Quartermaster', 'officer'), ('Observer', 'viewer')]:
                self.browser = owner
                status, created = self.http('/api/v1/users', {
                    'name': name, 'password': password, 'preset': role, 'mustChange': False,
                })
                require(status == 200, 'Owner could not provision the test member')
                identities[name] = created['user']['id']
                self.browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
                status, _ = self.http('/api/v1/auth/login', {'username': name, 'password': password})
                require(status == 200, 'Test member could not log in')
                users[name] = self.browser
                require(self.http('/api/v1/guild') == (200, guild), 'Member read a different guild profile')
            self.browser = users['Observer']
            status, rows = self.http('/api/v1/stock')
            require(status == 200 and self.amount(rows, 'minecraft:diamond', 'observed') == 96,
                    'Viewer did not observe the actual shared Minecraft stock')
            require(not rows['sources'], 'Viewer saw physical stock locations')
            draft = {'server': self.server_name, 'stash': 'e2e-fixture',
                     'lines': [{'type': 'item', 'key': 'minecraft:diamond', 'count': 1}]}
            require(self.http('/api/v1/orders', draft)[0] == 403, 'Viewer could order stock')
            self.browser = users['Builder']
            status, self_order = self.http('/api/v1/orders', draft)
            require(status == 200 and self_order['order']['purpose'] == 'SELF'
                    and self_order['order']['requestedBy'] == identities['Builder'],
                    'Member order did not record its independent requester')
            guild_draft = dict(draft, purpose='GUILD', comment='Projekt Å')
            require(self.http('/api/v1/orders', guild_draft)[0] == 403, 'Member bypassed the guild order permission')
            self.browser = users['Quartermaster']
            status, guild_order = self.http('/api/v1/orders', guild_draft)
            require(status == 200 and guild_order['order']['organizationId'] == guild['id']
                    and guild_order['order']['requestedBy'] == identities['Quartermaster'],
                    'Guild order did not reserve stock under the common organisation')
            query = urllib.parse.urlencode({'server': self.server_name, 'stash': 'e2e-fixture'})
            self.browser = users['Builder']
            status, history = self.http('/api/v1/orders?' + query)
            require(status == 200 and [order['id'] for order in history['orders']] == [self_order['order']['id']],
                    'Member order history included another requester')
            require('allocations' not in history['orders'][0], 'Member read physical order allocations')
            self.browser = owner
            require(self.http('/api/v1/users/' + identities['Builder'], {'disabled': True})[0] == 200,
                    'Owner could not revoke membership')
            self.browser = users['Quartermaster']
            for order in [self_order, guild_order]:
                require(self.http('/api/v1/orders/' + order['order']['id'] + '/cancel', {})[0] == 200,
                        'Officer lost control of a member order')
        finally:
            self.browser = owner
        self.ok('one real Fabric agent feeds a shared Docker guild stock; member, officer and viewer roles and recipient-independent order identity verified')

    def verify_order_lifecycle(self, row):
        self.rcon.command('setblock 8 64 -3 minecraft:chest')
        self.read(8, 'minecraft:air', 0)
        self.command('order-staging-settings', x=8, y=64, z=-3)
        root = '20' if self.approval else '0'
        group = next(slot['group'] for slot in row['snapshot']['positions'] if slot['path'] == root)
        draft = {'server': self.server_name, 'stash': 'e2e-fixture',
                 'lines': [{'type': 'shulker', 'key': group, 'count': 1}]}
        approved_order = None
        if self.approval:
            status, profile = self.http('/api/v1/guild')
            require(status == 200, 'Owner could not read the approval policy')
            update = {'name': profile['name'], 'description': profile['description'],
                      'approvalPolicy': 'GUILD', 'expectedRevision': profile['revision']}
            require(self.http('/api/v1/guild', update)[0] == 200, 'Owner could not set guild approval')
            require(self.http('/api/v1/users/owner/minecraft', {'account': 'FixtureRecipient'})[0] == 200,
                    'Owner could not confirm the recipient')
            status, placed = self.http('/api/v1/orders', dict(draft, purpose='GUILD', recipient='fixturerecipient'))
            require(status == 200 and placed['order']['approvalState'] == 'PENDING'
                    and placed['order']['recipient'] == 'FixtureRecipient', 'Guild order did not require explicit approval')
            approved_order = placed['order']['id']
        headers = {'Idempotency-Key': 'staging-fixture-order'}
        status, placed = self.http('/api/v1/orders', draft, headers=headers)
        require(status == 200 and placed['order']['state'] == 'RESERVED',
                'Physical order could not reserve a complete box: ' + str(placed.get('error')))
        require(self.http('/api/v1/orders', draft, headers=headers) == (200, placed), 'Order replay differed from its original receipt')
        ident = placed['order']['id']
        self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
        query = urllib.parse.urlencode({'server': self.server_name, 'stash': 'e2e-fixture'})

        def lookup(order_id):
            status, listing = self.http('/api/v1/orders?' + query)
            require(status == 200, 'Could not read orders from Manager')
            return next(entry for entry in listing['orders'] if entry['id'] == order_id)

        def ready(order_id):
            order = lookup(order_id)
            if order['state'] in ('FAILED', 'NEEDS_RECONCILIATION', 'DELIVERED'):
                raise RuntimeError('Unexpected order state ' + order['state'] + ': ' + str(order.get('message')))
            if order['state'] != 'PICKUP_READY':
                return False
            if self.remote_settings:
                require(order.get('packageCount') == 1 and 'manifest' not in order,
                        'A staged order must expose its count without leaking custody coordinates')
            return True

        if self.remote_settings:
            def pause_visible(_):
                status, stashes = self.http('/api/v1/stashes')
                return status == 200 and any(
                    entry.get('orderBlocker') == 'new warehouse orders paused by Manager' for entry in stashes)

            self.wait(pause_visible, 'Warehouse pause did not reach Manager heartbeat')
            require(lookup(ident)['state'] == 'RESERVED', 'Paused agent took a new order')
            require('Hierarchy box' not in self.rcon.command('data get block 8 64 -3 Items'),
                    'Paused agent moved a package')
            self.apply_remote_setting('order-intake-paused', False)
            self.ok('signed warehouse pause keeps a new physical order reserved until Manager resumes the same agent')

        self.wait(lambda _: ready(ident), 'Agent did not stage the actual Minecraft package')
        if self.remote_settings:
            self.wait(lambda _: f'Manager confirmed the staging manifest for {ident} (1 shulker packages)' in
                      (self.evidence / 'client.log').read_text(), 'Packing agent did not receive its signed staging manifest')
            require(lookup(ident).get('manifestAcknowledgedAt', 0) > 0, 'Manager did not save the signed manifest acknowledgement')
        stored = self.rcon.command('data get block 8 64 -3 Items')
        require(stored.count('Hierarchy box') == 1 and 'Exact sword' in stored, 'Pickup-ready package differs from the expected single shulker')
        status, audit = self.http('/api/v1/audit/orders?order=' + ident)
        require(status == 200 and len(audit['orders']) == 1, 'Owner could not find the staged order in the audit')
        traced = audit['orders'][0]
        require(len(traced['packageIds']) == 1 and traced['stagedAmounts'][0]['delivered'] == 1,
                'Audit did not preserve the verified staged package and count')
        require(any(operation['execution'] == 'STAGE_PACKAGE' and operation['state'] == 'COMPLETED'
                    for operation in traced['physicalOperations']), 'Audit did not correlate the verified staging operation')
        require('allocations' not in traced and 'manifest' not in traced, 'Audit contained raw slot evidence')
        self.ok('physical staging can be traced by order, package and verified operation from the scoped audit API')
        require(self.http('/api/v1/orders', draft, headers=headers) == (200, placed), 'Staged order replay created a new order')
        self.ok('an idempotent 26.3 Fabric order placed one exact named shulker in warehouse staging; Manager exposes pickup-ready custody')
        if approved_order is not None:
            waiting = lookup(approved_order)
            require(waiting['state'] == 'RESERVED' and waiting['approvalState'] == 'PENDING',
                    'Agent claimed an order awaiting review')
            status, reviewed = self.http('/api/v1/orders/' + approved_order + '/approval', {'decision': 'approve'})
            require(status == 200 and reviewed['order']['approvedBy'] == 'owner', 'Approval did not record its actor')
            self.wait(lambda _: ready(approved_order), 'Same agent did not stage the approved guild order')
            if self.remote_settings:
                self.wait(lambda _: f'Manager confirmed the staging manifest for {approved_order} (1 shulker packages)' in
                          (self.evidence / 'client.log').read_text(), 'Agent did not receive the approved order manifest')
                require(lookup(approved_order).get('manifestAcknowledgedAt', 0) > 0, 'Approved order has no durable manifest acknowledgement')
            stored = self.rcon.command('data get block 8 64 -3 Items')
            require(stored.count('Hierarchy box') == 2 and stored.count('Exact sword') == 2,
                    'Expected exactly two independently staged shulkers')
            self.ok('one Minecraft 26.3 agent stages SELF directly and GUILD after auditable approval from shared physical stock')

    def apply_remote_setting(self, path, value=None):
        agent_route = None
        schema = None

        def published(_):
            nonlocal agent_route, schema
            status, bots = self.http('/api/v1/bots')
            require(status == 200, 'Owner could not list agents')
            agent = next((bot for bot in bots if bot.get('player') == self.player), None)
            if agent is None:
                return False
            agent_route = '/api/v1/bots/' + agent['botId']
            status, schema = self.http(agent_route + '/settings?scope=plugin')
            return status == 200 and path in schema.get('values', {})

        self.wait(published, 'Fabric agent did not publish its actual supported settings')
        initial = schema['values'][path]
        changed = initial + 1 if value is None else value
        status, reply = self.http(agent_route + '/settings', {'scope': 'plugin', 'changes': [
            {'path': path, 'value': changed, 'expect': initial}
        ]})
        require(status == 200, 'Owner could not enqueue a typed agent setting')
        ident = reply['jobs'][0]['id']

        def observed(_):
            status, jobs = self.http(agent_route + '/jobs?ids=' + ident)
            require(status == 200, 'Owner could not read the command receipt')
            job = jobs[0]
            require(job['status'] not in ('error', 'expired', 'unknown'), 'Agent setting failed: ' + str(job))
            status, settings = self.http(agent_route + '/settings?scope=plugin')
            return job['status'] == 'done' and job['value'] == changed and status == 200 and (
                settings['values'][path] == changed)

        self.wait(observed, 'Manager did not receive application and refreshed settings from the live agent')
        status, audit = self.http('/api/v1/audit')
        require(status == 200 and sum(row.get('action') == 'agent.job.applied' and row.get('detail') == ident
                    for row in audit['entries']) == 1, 'Manager did not audit the acknowledged Fabric job')

    def verify_remote_settings(self):
        self.apply_remote_setting('scan-interval')
        self.apply_remote_setting('order-intake-paused', True)
        self.ok('Owner updates an allowlisted setting through Admin HTTP, signed lease, real Fabric execution and Manager receipt without opening a client GUI')

    def verify_return_intake(self, left, right):
        for source, mark, expected in [(left, False, 'VERIFIED_KEEP_NAME'), (right, True, 'UUID_REQUESTED')]:
            slot = next(item for item in source['slots'] if item['path'] == '0')
            draft = {'source': source['id'], 'path': '0', 'slotRevision': slot['revision'], 'markUuid': mark,
                     'expectedDecisionRevision': 0}
            require(self.http('/api/v1/stock/return-intake', dict(draft, slotRevision=slot['revision'] + 1))[0] == 409,
                    'A stale return selection could target a different shulker')
            status, receipt = self.http('/api/v1/stock/return-intake', draft)
            require(status == 200 and receipt.get('state') == expected and receipt.get('markUuid') == mark,
                    'Owner could not record the selected return handling')
            require(self.http('/api/v1/stock/return-intake', draft) == (200, receipt), 'Return replay changed the receipt')
            require(self.http('/api/v1/stock/return-intake', dict(draft, markUuid=not mark))[0] == 409,
                    'A browser with an older choice overwrote the current return decision')
            require(self.detail(source)['returnIntake'] == [receipt], 'Saved choice was missing from the source details')
        self.ok('return choices use verified shulkers in two ordinary warehouse containers and preserve the current item names')

    def require_scoped_agent(self):
        state = self.command('state')['state']
        require(state['legacyMemberWindowClassPresent'] is False, 'Legacy member portal class must not be shipped in production')
        # Key slots carry no bindings: any player may connect with a key, but only one at a time.
        player = state['playerUuid']
        cli = self.compose + ['exec', '-T', 'manager', '/opt/wesort/entrypoint']
        endpoint = f'http://127.0.0.1:{self.manager_port}'
        shared = stock.signed.Client(endpoint, self.key)
        old_token = self.run(cli + ['agent-issue', 'e2e-primary']).strip()
        old = stock.signed.Client(endpoint, old_token)
        self.key = self.run(cli + ['agent-issue', 'e2e-native']).strip()
        current = stock.signed.Client(endpoint, self.key)
        foreign_player = str(uuid.uuid4())
        foreign_token = self.run(cli + ['agent-issue', 'e2e-foreign']).strip()
        foreign = stock.signed.Client(endpoint, foreign_token)
        self.run(cli + ['agent-require-scoped'])
        # The stash-def gate is fail-closed: in-scope heartbeats for a second
        # stash need its definition (the owner session from start_manager persists).
        # other-stash is likewise defined so the second credential's own-scope
        # heartbeat is meaningful; unassigned-fixture stays undefined on purpose.
        for name in ('secondary-fixture', 'other-stash'):
            status, _ = self.http('/api/v1/stash-defs', {'action': 'create', 'name': name,
                'dimension': 'minecraft:overworld',
                'pos1': {'x': -32, 'y': 40, 'z': -32}, 'pos2': {'x': 63, 'y': 90, 'z': 32}})
            require(status == 200, '%s stash def failed: %s' % (name, status))
        heartbeat = {'server': self.server_name, 'stash': 'e2e-fixture', 'player': self.player, 'agent': player}
        require(shared.post('/api/v1/heartbeat', heartbeat)[0] == 401, 'Shared credential survived scoped-only activation')
        # Rotation is revoke + reissue: a revoked key stops authenticating at once.
        self.run(cli + ['agent-revoke', old_token.split('.')[0]])
        require(old.post('/api/v1/heartbeat', heartbeat)[0] == 401, 'Revoked credential could still act')
        require(current.post('/api/v1/heartbeat', heartbeat)[0] == 200, 'Current credential could not read its warehouse heartbeat')
        require(current.post('/api/v1/heartbeat', dict(heartbeat, stash='secondary-fixture'))[0] == 200,
                'One key slot could not reach a second defined stash')
        # One player at a time: the same key with another player is refused (409),
        # while any username works once the slot is free (covered with distinct keys here).
        require(current.post('/api/v1/heartbeat', dict(heartbeat, agent=foreign_player, player='OtherBot'))[0] == 409,
                'A second player was not refused on a held key slot')
        # No per-stash or Minecraft-username ownership: every key slot may operate
        # in every DEFINED stash. Undefined stash names must still fail closed (404),
        # and a different configured Minecraft server is rejected (409).
        require(current.post('/api/v1/heartbeat', dict(heartbeat, stash='unassigned-fixture'))[0] == 404,
                'Undefined stash was not refused with 404')
        require(current.post('/api/v1/heartbeat', dict(heartbeat, server='evil-server'))[0] == 409,
                'Heartbeat from a foreign server was not refused with 409')
        require(foreign.post('/api/v1/heartbeat', dict(heartbeat, agent=foreign_player, player='OtherBot'))[0] == 200,
                'Valid key slot was rejected for another Minecraft player')
        unauthorized = {'server': self.server_name, 'stash': 'other-stash', 'player': 'OtherBot', 'agent': foreign_player}
        require(foreign.post('/api/v1/heartbeat', unauthorized)[0] == 200, 'Second key slot was unusable in its own scope')
        self.run(cli + ['agent-revoke', foreign_token.split('.')[0]])
        require(foreign.post('/api/v1/heartbeat', unauthorized)[0] == 401, 'Revoked agent continued to authenticate')
        self.ok('unbound key slots bind one player at a time; scoped-only, revocation and fail-closed gates remain enforced')

    def verify_fleet_sessions(self):
        native = None

        def reported_activity(_):
            nonlocal native
            status, bots = self.http('/api/v1/bots')
            require(status == 200, 'Owner could not read the fleet')
            native = next((bot for bot in bots if bot.get('player') == self.player and bot['online'] and bot.get('summary')), None)
            return native is not None

        self.wait(reported_activity, 'The actual Fabric agent did not report its current task')
        require({'warehouse-scan', 'warehouse-sort', 'pack', 'stage'} <= set(native['caps']),
                'The actual Fabric agent did not advertise its supported warehouse work')
        require(native['lastLocation'] == {'server': self.server_name, 'stash': 'e2e-fixture', 'dimension': 'minecraft:overworld'},
                'Manager did not report the connected Minecraft server, stash and dimension')
        cli = self.compose + ['exec', '-T', 'manager', '/opt/wesort/entrypoint']
        endpoint = f'http://127.0.0.1:{self.manager_port}'
        expected = {native['botId']}
        credentials = []
        # Fleet peers bind their own stashes: bot/hello fail-closes 404 on an undefined
        # stash (BotRoutes unknownStash gate). Define all four up front so registration
        # tests session independence, not setup. Adding definitions is not ownership:
        # every authorized bot may still use every defined stash.
        for i in range(1, 5):
            status, _ = self.http('/api/v1/stash-defs', {'action': 'create', 'name': f'fleet-peer-{i}',
                'dimension': 'minecraft:overworld',
                'pos1': {'x': -32, 'y': 40, 'z': -32}, 'pos2': {'x': 63, 'y': 90, 'z': 32}})
            require(status == 200, 'fleet-peer-%d stash def failed: %s' % (i, status))
        for i in range(1, 5):
            ident, player, stash = str(uuid.uuid4()), f'FleetPeer{i}', f'fleet-peer-{i}'
            token = self.run(cli + ['agent-issue', f'fleet-peer-{i}']).strip()
            key_id = token.split('.')[0]
            agent = stock.signed.Client(endpoint, token)
            bot_id = key_id
            hello = {'botId': bot_id, 'player': player, 'agent': ident, 'controlSession': secrets.token_hex(32),
                     'manifestScope': {'server': self.server_name, 'stash': stash,
                                       'dimension': 'minecraft:overworld', 'agent': ident}}
            code, reply = agent.post('/api/v1/bot/hello', hello)
            if code != 200 or not (reply or {}).get('controlEpoch'):
                status, defs = self.http('/api/v1/stash-defs')
                names = sorted(d['name'] for d in defs.get('defs', [])) if status == 200 else defs
                raise RuntimeError(
                    'Independent fleet member could not register: hello=%s body=%s stash=%s defined=%s' % (
                        code,
                        {k: (v if k != 'controlSession' else '<redacted>') for k, v in (reply or {}).items()},
                        stash, names))
            require(agent.post('/api/v1/bot/hello', dict(hello, controlSession=secrets.token_hex(32)))[0] == 409,
                    'A concurrent control session displaced an online agent')
            require(agent.post('/api/v1/bot/next', {'botId': native['botId'], 'controlSession': hello['controlSession']})[0] == 403,
                    'A fleet member polled another agent')
            expected.add(bot_id)
            credentials.append((ident, player, stash, hello, token))
            status, connected = self.http('/api/v1/bots')
            require(status == 200 and {b['botId'] for b in connected if b['online']} == expected,
                    'Manager mixed sessions while expanding the fleet')
        ident, player, stash, hello, token = credentials[0]
        # Rotation is revoke + reissue: the old key stops authenticating, the new
        # key takes over the same player with a fresh slot identity.
        self.run(cli + ['agent-revoke', token.split('.')[0]])
        retired = stock.signed.Client(endpoint, token)
        require(retired.post('/api/v1/bot/hello', hello)[0] == 401,
                'Revoked key still registered a fleet session')
        replacement = self.run(cli + ['agent-issue', 'fleet-peer-1-again']).strip()
        rotated = stock.signed.Client(endpoint, replacement)
        new_hello = dict(hello, botId=replacement.split('.')[0])
        code, reply = rotated.post('/api/v1/bot/hello', new_hello)
        require(code == 200 and (reply or {}).get('controlEpoch'),
                'Reissued key could not register the same player again')
        expected.discard(hello['botId'])
        expected.add(new_hello['botId'])
        hello = new_hello
        self.ok('one native 26.3 agent and up to four signed endpoint fixtures retain independent online identities and sessions')
        status, metrics = self.http('/api/v1/operations')
        require(status == 200 and metrics['agents']['ONLINE'] == 5 and metrics['stock']['FRESH'] > 0,
                'Operational metrics did not reflect the five online sessions and the native stock observations')
        poll = {'botId': hello['botId'], 'controlSession': hello['controlSession'], 'controlEpoch': reply['controlEpoch'],
                'activity': {'incident': 'NO_CAPACITY'}}
        require(rotated.post('/api/v1/bot/next', dict(poll, controlEpoch=str(uuid.uuid4())))[0] == 409,
                'An old control epoch could publish an incident')
        require(rotated.post('/api/v1/bot/next', poll)[0] in (200, 204), 'Could not report a signed incident')
        status, metrics = self.http('/api/v1/operations')
        require(status == 200 and {'code': 'NO_CAPACITY', 'count': 1} in metrics['alerts'],
                'Manager did not classify the current capacity alert')
        self.ok('operations API reports current agents, native stock freshness and a session-verified capacity incident')

    def execute(self):
        try:
            self.start_server(); self.fixture(); self.start_manager(); self.start_client()
            self.command('stock-scan-settings')
            self.read(0); self.read(6); self.read(3, 'minecraft:dirt', 16)
            before = {str(x): self.rcon.command(f'data get block {x} 64 -3 Items') for x in [0, 3, 6]}
            initial = self.command('state')['state']['index']
            require(initial['physicalVersion'] == 2 and initial['slotVariants'], 'Actual client omitted physical tree')
            self.server_name = initial['server']
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            observed = self.wait(lambda s: all((row := self.source(s, x, -3)) is not None and row['state'] == 'FRESH' for x in [0, 6]), 'Native hierarchy did not reach Manager')
            if self.remote_settings:
                self.verify_remote_settings()
            self.pause()
            a = self.detail(self.source(observed, 0, -3)); b = self.detail(self.source(observed, 6, -3))
            if self.remote_settings:
                self.verify_return_intake(a, b)
            if self.reservations:
                self.verify_reservations(a, before)
            if self.guild:
                self.verify_guild()
            positions = {row['path']: row for row in a['snapshot']['positions']}
            other = {row['path']: row for row in b['snapshot']['positions']}
            require(positions['0']['fingerprint'] == positions['20']['fingerprint'] == other['0']['fingerprint'], 'Identical box contents differ by physical location')
            require(a['id'] != b['id'] and positions['0']['path'] != positions['20']['path'], 'Identical box locations collided')
            require(positions['0/26']['slot'] == 26 and positions['0']['size'] == 27 and 'Exact sword' in positions['0/26']['components']
                and 'minecraft:damage' in positions['0/26']['components'] and 'minecraft:enchantments' in positions['0/26']['components'], 'Sparse exact metadata was lost')
            require(positions['0/13/0']['id'] == 'minecraft:iron_ingot' and positions['0/13/0']['count'] == 8, 'Nested bundle inventory not modeled')
            require(self.amount(observed, 'minecraft:diamond', 'observed') == 96 and self.amount(observed, 'minecraft:iron_ingot', 'observed') == 32,
                'Native guild totals double counted inner items or missed existing outer bundle')
            after = {str(x): self.rcon.command(f'data get block {x} 64 -3 Items') for x in [0, 3, 6]}
            require(before == after, 'Read-only physical hierarchy changed server inventory')
            self.ok('native sparse 27-slot hierarchy, metadata and nested contents survive signed sync and SQLite; identical boxes stay separate and totals count each item once')
            (self.evidence / 'initial-details.json').write_text(json.dumps([a,b], indent=2)+'\n')
            (self.evidence / 'server-before.json').write_text(json.dumps(before, indent=2)+'\n')
            self.command('manager-unlink-test')
            reply = self.rcon.command('data modify block 0 64 -3 Items[{Slot:0b}].components."minecraft:container"[{slot:0}].item.count set value 31')
            require('modified' in reply.lower(), 'Server content mutation rejected: ' + reply)
            self.read(0)
            changed_index = self.command('state')['state']['index']
            old_source = next(s for s in initial['physicalSources'] if s['id']==a['id'])
            new_source = next(s for s in changed_index['physicalSources'] if s['id']==a['id'])
            require(new_source['fingerprint'] != old_source['fingerprint'], 'Native content change retained old snapshot fingerprint')
            self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
            changed = self.wait(lambda s: (row := self.source(s, 0, -3)) is not None and row['sourceRevision'] > a['sourceRevision']
                and row.get('slotFingerprint') != a['snapshot']['fingerprint'], 'New verified hierarchy did not replace old physical fingerprint')
            self.pause(); detail = self.detail(self.source(changed,0,-3))
            old_slots = {s['path']:s for s in a['slots']}; new_slots = {s['path']:s for s in detail['slots']}
            require(new_slots['0/0']['count']==31 and new_slots['0/0']['revision'] > old_slots['0/0']['revision'], 'Changed inner stack did not advance its physical revision')
            require(new_slots['20']['revision'] == old_slots['20']['revision'], 'Unchanged identical sibling acquired another slot identity/revision')
            replay = stock.signed.Client(f'http://127.0.0.1:{self.manager_port}', self.key)
            code, _ = replay.post('/api/v1/index', dict(initial, agent=self.command('state')['state']['playerUuid']))
            require(code == 409, 'Older actual snapshot accepted after changed content')
            self.ok('real server-side inner-content delta invalidates old box fingerprint and advances affected slot revisions; captured old signed observation rejected 409')
            if self.order_lifecycle or self.approval:
                if self.scoped_agent:
                    self.require_scoped_agent()
                # The physical mutation check stopped the agent. Restart it with the
                # currently issued key (the shared key is invalid in scoped-only mode)
                # before asserting that a Manager pause reaches its live heartbeat.
                self.command('publish', url=f'http://127.0.0.1:{self.manager_port}', key=self.key)
                self.verify_order_lifecycle(detail)
                if self.scoped_agent:
                    self.verify_fleet_sessions()
            self.command('screenshot'); shutil.copyfile(self.game_dir/'screenshots/fixture.png',self.evidence/'fixture.png')
            (self.evidence/'changed-detail.json').write_text(json.dumps(detail,indent=2)+'\n')
            result = {'result':'PASS','source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=world.ROOT,text=True).strip(),
                'dirty_worktree':True,'server_sha1':world.SERVER_SHA1,'manager_image':self.env['WESORT_IMAGE'],'checks':self.checks,
                'limits':'One native Minecraft agent with signed HTTP peers for fleet tests. Package custody is verified through warehouse staging.'}
            (self.evidence/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            print('Evidence: '+str(self.evidence),flush=True)
        finally: self.close()


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--manager-image',default=world.DEFAULT_IMAGE)
    parser.add_argument('--reservations', action='store_true')
    parser.add_argument('--guild', action='store_true')
    parser.add_argument('--order-lifecycle', action='store_true')
    parser.add_argument('--approval', action='store_true')
    parser.add_argument('--remote-settings', action='store_true')
    parser.add_argument('--scoped-agent', action='store_true')
    args = parser.parse_args()
    harness = HierarchyHarness(args.manager_image)
    harness.reservations = args.reservations
    harness.guild = args.guild
    harness.order_lifecycle = args.order_lifecycle
    harness.approval = args.approval
    harness.remote_settings = args.remote_settings
    harness.scoped_agent = args.scoped_agent
    harness.execute()
