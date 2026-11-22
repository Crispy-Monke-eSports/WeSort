#!/usr/bin/env python3
"""Real private vanilla 26.3 server + external Fabric client + Docker Manager.

All mutable data is created beneath a new marked build directory. No existing
Minecraft/Prism world, account, Qwerte data or Manager installation is opened.
"""
import argparse
from collections import Counter
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SERVER_URL = 'https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar'
SERVER_SHA1 = '33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c'
DEFAULT_IMAGE = 'ghcr.io/crispy-monke-esports/wesort-manager@sha256:e08a41b306d135ba8da8931c1fbd2d99483d83bda4e094efac0841b6177b9b69'


def require(value, message):
    if not value:
        raise RuntimeError(message)


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Rcon:
    def __init__(self, port, password):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=10)
        self.sequence = 10
        self.send(1, 3, password)
        while True:
            ident, kind, _ = self.receive()
            require(ident != -1, 'RCON authentication rejected')
            if kind == 2:
                require(ident == 1, 'Unexpected RCON authentication response')
                break

    def send(self, ident, kind, value):
        data = struct.pack('<ii', ident, kind) + value.encode() + b'\0\0'
        self.socket.sendall(struct.pack('<i', len(data)) + data)

    def read(self, length):
        result = b''
        while len(result) < length:
            block = self.socket.recv(length - len(result))
            require(block, 'RCON disconnected')
            result += block
        return result

    def receive(self):
        length = struct.unpack('<i', self.read(4))[0]
        require(10 <= length <= 4 * 1024 * 1024, 'Invalid RCON frame')
        packet = self.read(length)
        ident, kind = struct.unpack('<ii', packet[:8])
        return ident, kind, packet[8:-2].decode()

    def command(self, command):
        self.sequence += 1
        ident = self.sequence
        self.send(ident, 2, command)
        # Vanilla RCON expects serialized requests; do not pipeline TCP frames.
        # These fixture queries are deliberately bounded below one response frame.
        got, _, data = self.receive()
        require(got == ident, 'Unexpected RCON response id')
        require(len(data.encode()) < 4096, 'RCON fixture result too large; query a narrower NBT path')
        return data


class Harness:
    def __init__(self, image):
        os.umask(0o077)
        self.id = uuid.uuid4().hex[:10]
        self.root = ROOT / 'build' / ('mc26-e2e-' + self.id)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=False)
        (self.root / 'installation.txt').write_text('wesort-mc26-e2e-v1\n')
        self.server_dir = self.root / 'server'; self.server_dir.mkdir()
        self.game_dir = self.root / 'client'; self.game_dir.mkdir()
        (self.game_dir / 'options.txt').write_text('onboardAccessibility:false\nskipMultiplayerWarning:true\ntutorialStep:none\nstartedCleanly:true\npauseOnLostFocus:false\n')
        self.control = self.game_dir / 'wesort-e2e'; self.control.mkdir()
        (self.control / 'installation.txt').write_text('wesort-mc26-e2e-v1\n')
        self.evidence = self.root / 'evidence'; self.evidence.mkdir()
        self.server_port, self.rcon_port, self.manager_port = [free_port() for _ in range(3)]
        self.password = secrets.token_hex(32)
        self.env = dict(os.environ, WESORT_IMAGE=image, WESORT_PORT=str(self.manager_port),
            WESORT_BIND='127.0.0.1', WESORT_SECURE_COOKIES='false', WESORT_EULA='true',
            WESORT_ALLOWED_HOSTS='', WESORT_TRUSTED_PROXIES='')
        self.compose = ['docker', 'compose', '-p', 'wesort-e2e-' + self.id, '-f', str(ROOT / 'compose.yaml')]
        self.server = self.client = self.rcon = None
        self.browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.checks = []
        self.logs = []

    def run(self, command, **kwargs):
        result = subprocess.run(command, cwd=ROOT, env=self.env, text=True, capture_output=True,
            timeout=kwargs.pop('timeout', 180), **kwargs)
        require(result.returncode == 0, 'Command failed: ' + ' '.join(command[:3]))
        return result.stdout

    def ok(self, name):
        self.checks.append({'scenario': name, 'result': 'PASS'})
        print('PASS: ' + name, flush=True)

    def start_server(self):
        cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/fabric-loom/26.3/minecraft-server.jar'
        target = self.server_dir / 'server.jar'
        if cache.exists() and hashlib.sha1(cache.read_bytes()).hexdigest() == SERVER_SHA1:
            shutil.copyfile(cache, target)
        else:
            with urllib.request.urlopen(SERVER_URL, timeout=90) as source:
                target.write_bytes(source.read())
        require(hashlib.sha1(target.read_bytes()).hexdigest() == SERVER_SHA1, 'Server download checksum mismatch')
        (self.server_dir / 'eula.txt').write_text('eula=true\n')
        (self.server_dir / 'server.properties').write_text('\n'.join([
            'server-ip=127.0.0.1', f'server-port={self.server_port}', 'online-mode=false',
            'enforce-secure-profile=false', 'enable-query=false', 'enable-rcon=true',
            'white-list=false', 'enforce-whitelist=false',
            f'rcon.port={self.rcon_port}', f'rcon.password={self.password}',
            'broadcast-rcon-to-ops=false', 'level-name=wesort-e2e-world', 'level-seed=20261116',
            'level-type=minecraft:flat', 'gamemode=survival', 'difficulty=peaceful',
            'generator-settings={"biome":"minecraft:plains","features":false,"lakes":false,"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}',
            'spawn-protection=0', 'view-distance=3', 'simulation-distance=3', 'max-players=2',
            'motd=WeSort isolated 26.3 E2E fixture', 'accepts-transfers=false',
        ]) + '\n')
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
        log = (self.evidence / 'server.log').open('w'); self.logs.append(log)
        self.server = subprocess.Popen([java, '-Xms256m', '-Xmx1024m', '-jar', str(target), 'nogui'],
            cwd=self.server_dir, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True,
            start_new_session=True)
        for _ in range(180):
            require(self.server.poll() is None, 'Minecraft server stopped before readiness')
            try:
                self.rcon = Rcon(self.rcon_port, self.password)
                break
            except (ConnectionError, OSError):
                time.sleep(1)
        require(self.rcon is not None, 'Minecraft server readiness timeout')
        self.ok('real private vanilla 26.3 server, checksum verified, loopback only')

    def fixture(self):
        self.rcon.command('forceload add -16 -16 47 15')
        for _ in range(120):
            loaded = self.rcon.command('execute if loaded -8 64 -8 if loaded 40 64 12 run time query gametime')
            if re.search(r'time is \d+', loaded):
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('Fixture chunks did not load')
        commands = ['setworldspawn 0 64 0', 'time set day', 'weather clear',
            'fill -8 63 -8 40 63 12 minecraft:smooth_stone', 'fill -8 64 -8 40 70 12 minecraft:air']
        for x in range(0, 30, 3):
            commands += [f'setblock {x} 64 3 minecraft:chest[facing=north,type=left]',
                f'setblock {x+1} 64 3 minecraft:chest[facing=north,type=right]',
                f'item replace block {x} 64 3 container.0 with minecraft:diamond 64',
                f'item replace block {x+1} 64 3 container.0 with minecraft:diamond 64']
        commands += [
            'setblock 0 64 -3 minecraft:barrel',
            'item replace block 0 64 -3 container.0 with minecraft:diamond 32',
            'item replace block 0 64 -3 container.1 with minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:dirt",count:64}}],minecraft:custom_name={text:"E2E box"}] 1',
            'item replace block 0 64 -3 container.2 with minecraft:diamond_sword[minecraft:enchantments={"minecraft:unbreaking":1},minecraft:damage=3,minecraft:custom_name={text:"E2E sword"}] 1',
            'item replace block 0 64 -3 container.3 with minecraft:bundle[minecraft:bundle_contents=[{id:"minecraft:iron_ingot",count:8}]] 1',
            'setblock 3 64 -3 minecraft:shulker_box',
            'item replace block 3 64 -3 container.0 with minecraft:dirt 16',
            'setblock 6 64 -3 minecraft:barrel', 'setblock 9 64 -3 minecraft:crafting_table',
            'setblock 12 64 -3 minecraft:anvil', 'setblock 0 65 -3 minecraft:oak_sign',
            'data merge block 0 65 -3 {front_text:{messages:[{text:"Diamonds"},{text:"Tier 1"},{text:""},{text:""}]}}',
        ]
        results = []
        for command in commands:
            reply = self.rcon.command(command)
            require(not any(word in reply for word in ['Unknown', 'Incorrect', 'Expected', 'Invalid', 'Unclosed', 'Could not', 'Cannot', 'not loaded', 'not a block entity']),
                'Fixture command rejected: ' + command + ': ' + reply)
            results.append({'command': command, 'reply': reply})
        (self.evidence / 'fixture.json').write_text(json.dumps(results, indent=2) + '\n')
        self.ok('ten double chests, barrel, sign, shulker, bundle, named enchanted damaged item, crafting/anvil/staging fixtures')

    def physical_snapshot(self):
        positions = [(x, 64, 3) for x in range(30) if x % 3 != 2] + [(0, 64, -3), (3, 64, -3), (6, 64, -3)]
        result = {','.join(map(str, p)): self.rcon.command('data get block ' + ' '.join(map(str, p)) + ' Items') for p in positions}
        for pos, value in result.items():
            require('[' in value and ']' in value, 'No authoritative server item list for ' + pos + ': ' + value)
        return result

    def http(self, path, body=None, *, headers=None, timeout=10):
        headers = {'Content-Type': 'application/json', 'X-Stash-Manager': '1', **(headers or {})}
        request = urllib.request.Request('http://127.0.0.1:' + str(self.manager_port) + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            response = self.browser.open(request, timeout=timeout)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    def manager_server_name(self):
        # What the test client reports as its server: the address it connected to.
        return '127.0.0.1:' + str(self.server_port)

    def start_manager(self):
        # Docker readiness and Owner bootstrap are real requirements, not fixed sleeps.
        # Preserve bounded diagnostics from the *compose up* command only: generic
        # run() can return tokens/keys for bootstrap and must never log those values.
        command = self.compose + ['up', '-d', '--no-build']
        started = subprocess.run(command, cwd=ROOT, env=self.env, text=True,
                                 capture_output=True, timeout=180)
        if started.returncode:
            (self.evidence / 'manager-startup.json').write_text(json.dumps({
                'stage': 'compose-up', 'exit_code': started.returncode,
                'compose_stdout_tail': started.stdout[-1500:],
                'compose_stderr_tail': started.stderr[-3000:],
            }, indent=2) + '\n')
            raise RuntimeError('Docker Manager compose startup failed; see manager-startup.json')

        deadline = time.monotonic() + 150
        last = 'No response'
        while time.monotonic() < deadline:
            try:
                code, _ = self.http('/health/ready', timeout=5)
                if code == 200:
                    break
                last = 'HTTP %d' % code
            except (OSError, urllib.error.URLError) as failed:
                last = type(failed).__name__
            time.sleep(.5)
        else:
            # Docker container state is useful evidence and does not contain
            # bootstrap tokens, account passwords or API keys.
            ps = subprocess.run(self.compose + ['ps', '--format', 'json'],
                                cwd=ROOT, env=self.env, text=True,
                                capture_output=True, timeout=15)
            (self.evidence / 'manager-startup.json').write_text(json.dumps({
                'stage': 'health-ready', 'last_probe': last,
                'compose_ps_exit_code': ps.returncode,
                'compose_ps_tail': ps.stdout[-3000:],
                'compose_error_tail': ps.stderr[-1000:],
            }, indent=2) + '\n')
            raise RuntimeError('Docker Manager readiness timeout (%s); see manager-startup.json' % last)

        token = self.run(self.compose + ['exec', '-T', 'manager', '/opt/wesort/entrypoint', 'bootstrap']).strip()
        owner_password = secrets.token_urlsafe(32)
        require(self.http('/api/v1/auth/setup',
                          {'code': token, 'password': owner_password}, timeout=30)[0] == 200,
                'Owner bootstrap failed')
        # The Manager's single-server gate (400 without a server, 404 without a
        # stash def) is fail-closed by design: a real deployment configures both
        # before any bot publishes. The owner session (cookie jar) sets the one
        # Minecraft server to exactly what the test client reports as its
        # connection address, and defines e2e-fixture over the fixture area.
        # Password verification may be delayed by a cold Docker/SQLite bootstrap.
        # Retry only transport failures of the same correct Owner credentials;
        # HTTP auth rejection is never retried or accepted.
        login_deadline = time.monotonic() + 75
        while True:
            try:
                login_status, _ = self.http('/api/v1/auth/login',
                                            {'password': owner_password}, timeout=25)
                require(login_status == 200, 'Owner login failed (HTTP %d)' % login_status)
                break
            except (OSError, urllib.error.URLError) as failed:
                if time.monotonic() >= login_deadline:
                    raise RuntimeError(
                        'Manager Owner login transport failed after readiness: %s'
                        % type(failed).__name__) from failed
                time.sleep(1)
        status, srv = self.http('/api/v1/server', {'server': self.manager_server_name()})
        require(status == 200 and srv.get('configured'), 'Manager server setup failed: %s %s' % (status, srv))
        status, _ = self.http('/api/v1/stash-defs', {'action': 'create', 'name': 'e2e-fixture',
            'dimension': 'minecraft:overworld',
            'pos1': {'x': -32, 'y': 40, 'z': -32}, 'pos2': {'x': 63, 'y': 90, 'z': 32}})
        require(status == 200, 'e2e-fixture stash def failed: %s' % status)
        status, link = self.http('/api/v1/link')
        require(status == 200 and 'apiKey' not in link, 'Owner link exposed an agent secret')
        self.key = self.run(self.compose + ['exec', '-T', 'manager', '/opt/wesort/entrypoint', 'agent-key']).strip()
        self.ok('Docker Manager from explicit image with fresh volume and safe Owner setup, no Discord')

    def start_client(self):
        log = (self.evidence / 'client.log').open('w'); self.logs.append(log)
        self.client = subprocess.Popen([str(ROOT / 'gradlew'), '--no-daemon', 'runClient', '-Pmc=26.3',
            '-PwithBaritone=true', '-PworldTest=true', '-PgameDir=' + str(self.game_dir),
            '-PtestServer=127.0.0.1:' + str(self.server_port)], cwd=ROOT, stdout=log,
            stderr=subprocess.STDOUT, env=self.env, start_new_session=True)
        # Don't issue a command until the actual connected game is observed by the driver.
        state = self.command('state', timeout=180)['state']
        require(state['dimension'] == 'minecraft:overworld', 'Wrong test dimension')
        require(re.fullmatch(r'[A-Za-z0-9_]{1,16}', state['player']), 'Unexpected test player name')
        self.player = state['player']
        for _ in range(150):
            state = self.command('state')['state']
            if state['screen'] == 'none':
                # The vanilla loading screen sends PlayerLoaded before it closes.
                # An existing LocalPlayer alone does not mean the server accepts interactions yet.
                time.sleep(0.5)
                break
            time.sleep(0.2)
        else:
            raise RuntimeError('Client never completed vanilla terrain loading')
        self.ok('external real 26.3 Fabric/Meteor/WeSort client connected to private server')

    def command(self, action, timeout=25, allow_failure=False, **values):
        ident = uuid.uuid4().hex
        tmp = self.control / 'request.tmp'
        tmp.write_text(json.dumps(dict(id=ident, action=action, **values)))
        tmp.replace(self.control / 'request.json')
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            require(self.client.poll() is None, 'Fabric client stopped; inspect the private test evidence log')
            response = self.control / 'response.json'
            if response.exists():
                value = json.loads(response.read_text())
                if value['id'] == ident:
                    with (self.evidence / 'actions.jsonl').open('a') as journal:
                        journal.write(json.dumps({'action': action, 'response': value}) + '\n')
                    require(value['success'] or allow_failure, 'Client refused: ' + value['message'])
                    return value
            time.sleep(0.1)
        raise RuntimeError('Client control timed out; no physical action is assumed complete')

    def settle_test_menus(self, quiet=2.5, timeout=20):
        """E2E-only cleanup of a late OpenScreen packet from fixture observation.

        The client has no human operator. Closing a leftover vanilla menu does
        not move items, fabricate a receipt, or bypass any physical work fence.
        Do not enter a new physical phase until the screen is stably released.
        """
        end = time.monotonic() + timeout
        quiet_since = None
        while time.monotonic() < end:
            state = self.command('state')['state']
            if state['screen'] != 'none' or state['containerId'] != 0:
                self.command('close')
                quiet_since = None
            else:
                if quiet_since is None: quiet_since = time.monotonic()
                if time.monotonic() - quiet_since >= quiet:
                    return
            time.sleep(.2)
        raise RuntimeError('Fixture did not release late container menu without inventory mutation')

    def teleport(self, x, z=0.5):
        self.rcon.command(f'tp {self.player} {float(x)} 64 {float(z)}')
        for _ in range(50):
            state = self.command('state')['state']
            if abs(state['x'] - x) < 0.1 and abs(state['z'] - z) < 0.1:
                return
            time.sleep(0.1)
        raise RuntimeError('Server teleport not observed')

    def observe(self):
        snapshots = []
        for x in range(0, 30, 3):
            self.teleport(x + 0.5, 2.0)
            snapshots.append(self.command('open', x=x, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=x+1))
            self.command('close')
        self.ok('ten real double chests opened with 54 slots and stable server-supplied contents')
        self.teleport(0.5, -1.5)
        snapshots.append(self.command('open', x=0, y=64, z=-3, slots=27, item='minecraft:diamond', count=32))
        self.command('close')
        self.teleport(3.5, -1.5)
        snapshots.append(self.command('open', x=3, y=64, z=-3, slots=27, item='minecraft:dirt', count=16))
        self.command('close')
        (self.evidence / 'client-observations.json').write_text(json.dumps(snapshots, indent=2) + '\n')
        self.ok('barrel and placed shulker opened with real slot/state ids and item component fingerprints')
        return snapshots[-1]['state']['index']

    def verify_manager_index(self, client_index):
        self.command('publish', url='http://127.0.0.1:' + str(self.manager_port), key=self.key)
        query = urllib.parse.urlencode({'server': client_index['server'], 'stash': 'e2e-fixture'})
        end = time.monotonic() + 60
        stored = None
        while time.monotonic() < end:
            status, body = self.http('/api/v1/index?' + query)
            if status == 200:
                stored = body; break
            time.sleep(0.5)
        require(stored is not None, 'Production signed Fabric agent did not publish to Docker Manager')
        require(stored['mcVersion'] == '26.3', 'Wrong Manager index version')
        counts = Counter()
        for item in stored['items']:
            counts[item['id']] += item['count']
        expected = {'minecraft:diamond': 1312, 'minecraft:dirt': 80, 'minecraft:iron_ingot': 8,
            'minecraft:diamond_sword': 1, 'minecraft:shulker_box': 1, 'minecraft:bundle': 1}
        require(dict(counts) == expected, 'Unexpected observed Manager totals: ' + str(dict(counts)))
        identity = lambda index: {item['key']: item['count'] for item in index['items']}
        require(identity(stored) == identity(client_index), 'Client and Manager fingerprints/totals differ')
        (self.evidence / 'manager-index.json').write_text(json.dumps(stored, indent=2) + '\n')
        self.ok('production signed Fabric agent index reaches Docker Manager with matching fresh 26.3 totals and fingerprints')

    def close(self):
        if self.client is not None and self.client.poll() is None:
            try:
                self.command('stop', timeout=10)
                self.client.wait(timeout=30)
            except Exception:
                os.killpg(self.client.pid, signal.SIGTERM)
                try: self.client.wait(timeout=15)
                except subprocess.TimeoutExpired: os.killpg(self.client.pid, signal.SIGKILL)
        if self.rcon is not None:
            self.rcon.socket.close()
        if self.server is not None and self.server.poll() is None:
            try:
                self.server.stdin.write('stop\n'); self.server.stdin.flush()
                self.server.wait(timeout=45)
            except Exception:
                os.killpg(self.server.pid, signal.SIGTERM)
        try:
            self.run(self.compose + ['down', '-v', '--remove-orphans'])
        finally:
            for log in self.logs: log.close()

    def execute(self):
        try:
            self.start_server(); self.fixture()
            before = self.physical_snapshot()
            (self.evidence / 'server-before.json').write_text(json.dumps(before, indent=2) + '\n')
            self.start_manager(); self.start_client()
            client_index = self.observe()
            self.command('screenshot')
            shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'fixture.png')
            self.verify_manager_index(client_index)
            after = self.physical_snapshot()
            require(after == before, 'Physical inventory changed during read-only fixture observation')
            (self.evidence / 'server-after.json').write_text(json.dumps(after, indent=2) + '\n')
            self.ok('authoritative server container SNBT unchanged after all observations')
            result = {'result': 'PASS', 'server_sha1': SERVER_SHA1, 'manager_image': self.env['WESORT_IMAGE'],
                'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'dirty_worktree': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
                'checks': self.checks, 'limits': 'Read-only world/agent/Manager harness. Inventory movement, sorting, kit/repack, navigation and fault injection require their own scenarios.'}
            (self.evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally:
            self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', default=DEFAULT_IMAGE)
    options = parser.parse_args()
    Harness(options.manager_image).execute()
