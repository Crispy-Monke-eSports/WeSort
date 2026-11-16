#!/usr/bin/env python3
"""Linux Manager process smoke: fresh start, signed order, restart and backup leave legacy fixtures unchanged."""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import re
import socket
import subprocess
import tarfile
import tempfile
import time
import urllib.request

repo = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, default=repo / 'stash-manager-server/build/libs/wesort-manager.jar')
parser.add_argument('--java', default='java')
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('fake_addon', repo / 'stash-manager-server/dev/fake-addon.py')
fake = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fake)

with tempfile.TemporaryDirectory(prefix='wesort-isolation-') as directory:
    root = Path(directory)
    legacy = root / 'stash-manager-data'
    legacy.mkdir()
    (legacy / 'users.json').write_bytes(b'corrupt legacy users\x00\xff')
    (legacy / 'config.properties').write_text('apiKey=LEGACY-SENTINEL\n')
    (legacy / 'orders.json').write_text('old order must never appear')
    client_old = root / 'meteor-client/qwerte'
    client_old.mkdir(parents=True)
    (client_old / 'kits.nbt').write_bytes(b'\xff\x00bad nbt')
    files = list(legacy.iterdir()) + list(client_old.iterdir())
    for file in files:
        file.chmod(0o400)
    def snapshot():
        return {str(p.relative_to(root)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns, p.stat().st_mode) for p in files}
    before = snapshot()
    data = root / 'wesort-manager-data'
    data.mkdir()
    (data / '.wesort-installation').write_text('wesort-manager:1\n')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    (data / 'config.properties').write_text(f'port={port}\nbind=127.0.0.1\niconSource=game\n')
    base = f'http://127.0.0.1:{port}'
    cookies = http.cookiejar.CookieJar()
    browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    def request(path, body=None):
        headers = {}
        if body is not None:
            headers = {'Content-Type': 'application/json', 'X-Stash-Manager': '1'}
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with browser.open(req, timeout=5) as response:
            return json.load(response)
    def start():
        log = root / 'manager.log'
        with log.open('w') as out:
            process = subprocess.Popen([args.java, '-jar', str(args.jar.resolve()), '--data', str(data)], cwd=root, stdout=out, stderr=subprocess.STDOUT)
        for _ in range(100):
            if process.poll() is not None:
                raise AssertionError('Manager exited before startup; temporary log contains details')
            try:
                state = request('/api/v1/auth/state')
                return process, log, state
            except (OSError, ValueError):
                time.sleep(0.1)
        process.terminate()
        process.wait(timeout=10)
        raise AssertionError('Manager did not become ready')
    def stop(process):
        process.terminate()
        process.wait(timeout=10)
        assert process.returncode in (0, 143, -15), process.returncode
    process, log, state = start()
    try:
        assert state['setupNeeded']
        token = subprocess.check_output([args.java, '-jar', str(args.jar.resolve()), 'bootstrap', '--data', str(data)], text=True).strip()
        assert token not in log.read_text()
        password = 'wesort-isolation-test-only-8392'
        request('/api/v1/auth/setup', {'code': token, 'password': password})
        request('/api/v1/auth/login', {'password': password})
        assert request('/api/v1/stashes') == []
        assert any(c.name == 'wesort_session' for c in cookies)
        key = re.search(r'^apiKey[=:](.+)$', (data / 'config.properties').read_text(), re.M).group(1).strip()
        assert key != 'LEGACY-SENTINEL'
        agent = fake.Client(base, key)
        index = fake.build_index()
        assert agent.post('/api/v1/index', index)[0] == 200
        assert fake.heartbeat(agent, index['indexHash'])[0] == 200
        order = request('/api/v1/orders', {'server': fake.SERVER, 'stash': fake.STASH,
            'lines': [{'type': 'item', 'key': 'minecraft:diamond', 'count': 1}]})
        order_id = order['order']['id']
        status, claim = agent.post('/api/v1/orders/claim', {'server': fake.SERVER, 'stash': fake.STASH})
        assert status == 200 and claim['order']['id'] == order_id
        assert snapshot() == before
    finally:
        stop(process)
    process, _, state = start()
    try:
        assert not state['setupNeeded']
        request('/api/v1/auth/login', {'password': password})
        assert len(request('/api/v1/stashes')) == 1
        assert snapshot() == before
    finally:
        stop(process)
    archive = root / 'wesort-backup.tar'
    with tarfile.open(archive, 'w') as backup:
        backup.add(data, arcname='wesort')
    with tarfile.open(archive) as backup:
        assert all(n == 'wesort' or n.startswith('wesort/') for n in backup.getnames())
        assert not any('qwerte' in n or 'stash-manager-data' in n for n in backup.getnames())
    assert snapshot() == before
    # A Qwerte directory must be rejected before config parsing or temp cleanup.
    refused = subprocess.run([args.java, '-jar', str(args.jar.resolve()), '--data', str(legacy)], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    assert refused.returncode != 0
    assert snapshot() == before
    assert not (legacy / '.wesort-installation').exists()
print('PASS: fresh Manager, signed index/heartbeat/order/claim, restart and isolated backup; legacy SHA-256/mtime/mode unchanged.')
