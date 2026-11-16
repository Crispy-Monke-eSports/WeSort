#!/usr/bin/env python3
"""Isolated Linux Docker smoke; removes only its own uniquely named project, volume and TLS helper."""
import argparse
import hashlib
from http.cookiejar import CookieJar
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

repo = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--image', default='wesort-manager:dev')
p.add_argument('--compose-file', default='compose.dev.yaml')
p.add_argument('--report', type=Path)
args = p.parse_args()
project = 'wesort-smoke-' + uuid.uuid4().hex[:10]
proxy_name = project + '-tls'
python_image = 'python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e'
env = dict(os.environ, WESORT_DEV_IMAGE=args.image, WESORT_IMAGE=args.image,
    WESORT_BIND='127.0.0.1', WESORT_ALLOWED_HOSTS='', WESORT_TRUSTED_PROXIES='', WESORT_ICON_SOURCE='game', WESORT_SECURE_COOKIES='false')
with socket.socket() as s:
    s.bind(('127.0.0.1', 0)); env['WESORT_PORT'] = str(s.getsockname()[1])
base = 'http://127.0.0.1:' + env['WESORT_PORT']
compose = ['docker', 'compose', '-p', project, '-f', str(repo / args.compose_file)]
checks = []

def run(command, *, input=None, check=True, timeout=180):
    result = subprocess.run(command, input=input, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=env, cwd=repo, timeout=timeout)
    if check and result.returncode:
        # Never emit a possibly credential-bearing response or Docker log on failure.
        raise AssertionError(f'Command failed: {command[0:3]}, exit={result.returncode}; inspect the named test project locally')
    return result

def ok(name):
    checks.append(name)
    print('PASS: ' + name, flush=True)

def wait_ready(container):
    for _ in range(120):
        info = json.loads(run(['docker', 'inspect', container]).stdout)[0]
        if not info['State']['Running']:
            raise AssertionError('Manager stopped before readiness')
        if info['State'].get('Health', {}).get('Status') == 'healthy': return
        time.sleep(0.5)
    raise AssertionError('Manager healthcheck did not become healthy')

def http(path, body=None, *, opener=None, origin=None, host=None, extra=None):
    headers = dict(extra or {})
    if body is not None: headers.update({'Content-Type': 'application/json', 'X-Stash-Manager': '1'})
    if origin: headers['Origin'] = origin
    if host: headers['Host'] = host
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try: response = (opener or urllib.request.build_opener()).open(request, timeout=15)
    except urllib.error.HTTPError as e: response = e
    with response:
        raw = response.read()
        try: value = json.loads(raw)
        except (ValueError, UnicodeError): value = None
        return response.status, value, response.headers, raw

spec = importlib.util.spec_from_file_location('fake', repo / 'stash-manager-server/dev/fake-addon.py')
fake = importlib.util.module_from_spec(spec); spec.loader.exec_module(fake)
agent_program = '''
import importlib.util,json,sys
s=importlib.util.spec_from_file_location('fake','/test/fake.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
r=json.load(sys.stdin);c=m.Client(r['url'],r['key'],bearer=r.get('bearer',False))
status,body=c.request(r.get('method','POST'),r['path'],r.get('body'))
print(json.dumps({'status':status,'body':body}))
'''

def agent(network, key, path, body=None, **options):
    payload = json.dumps(dict(key=key, url=agent_url, path=path, body=body, **options))
    command = ['docker', 'run', '--rm', '-i', '--network', network, '--mount',
        f'type=bind,src={repo / "stash-manager-server/dev/fake-addon.py"},dst=/test/fake.py,readonly',
        python_image, 'python', '-c', agent_program]
    return json.loads(run(command, input=payload).stdout)

container = None
try:
    run(compose + ['up', '-d', '--no-build'])
    container = run(compose + ['ps', '-q', 'manager']).stdout.strip()
    wait_ready(container)
    info = json.loads(run(['docker', 'inspect', container]).stdout)[0]
    network = next(iter(info['NetworkSettings']['Networks']))
    agent_url = 'http://' + info['NetworkSettings']['Networks'][network]['IPAddress'] + ':8765'
    volume = next(m['Name'] for m in info['Mounts'] if m['Destination'] == '/var/lib/wesort')
    assert run(['docker', 'exec', container, 'id', '-u']).stdout.strip() == '10001'
    assert info['HostConfig']['ReadonlyRootfs']
    assert run(['docker', 'exec', container, 'sh', '-c', 'touch /opt/wesort/forbidden'], check=False).returncode != 0
    assert run(['docker', 'exec', container, 'stat', '-c', '%a', '/var/lib/wesort', '/var/lib/wesort/config.properties']).stdout.split() == ['700', '600']
    assert http('/health/live')[1] == {'status': 'live'}
    assert http('/health/ready')[1] == {'status': 'ready'}
    assert http('/')[0] == 200
    assert http('/health/ready', host='evil.invalid')[0] == 403
    ok('non-root, read-only rootfs, owner-only volume, real healthcheck and same-container web/API')
    assert http('/api/v1/auth/setup', {'code': 'FORGED', 'password': 'docker-test-password-8392'},
        extra={'X-Forwarded-For': '127.0.0.1', 'Forwarded': 'for=127.0.0.1'})[0] == 403
    token = run(['docker', 'exec', container, '/opt/wesort/entrypoint', 'bootstrap']).stdout.strip()
    cookies = CookieJar()
    browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    password = 'docker-test-password-8392'
    assert http('/api/v1/auth/setup', {'code': token, 'password': password}, opener=browser, origin='https://evil.invalid')[0] == 403
    assert http('/api/v1/auth/setup', {'code': token, 'password': password}, opener=browser)[0] == 200
    assert http('/api/v1/auth/setup', {'code': token, 'password': password}, opener=browser)[0] == 409
    assert run(['docker', 'exec', container, '/opt/wesort/entrypoint', 'bootstrap'], check=False).returncode != 0
    assert http('/api/v1/stashes', opener=browser)[1] == []
    key = http('/api/v1/link', opener=browser)[1]['apiKey']
    log_result = run(['docker', 'logs', container])
    logs = log_result.stdout + log_result.stderr
    assert token not in logs and key not in logs and password not in logs
    ok('Docker bridge Owner bootstrap: local CLI capability, origin checks, replay denied, no secrets in logs')
    index = fake.protocol_observation(fake.build_index())
    assert agent(network, key, '/api/v1/index', index)['status'] == 200
    assert agent(network, key, '/api/v1/heartbeat', {'server': fake.SERVER, 'stash': fake.STASH, 'player': fake.PLAYER,
        'indexHash': index['indexHash'], 'delivery': fake.DELIVERY})['status'] == 200
    assert agent(network, 'WRONG', '/api/v1/index', index)['status'] == 401
    assert agent(network, key, '/api/v1/index', index, bearer=True)['status'] == 401
    order = http('/api/v1/orders', {'server': fake.SERVER, 'stash': fake.STASH,
        'lines': [{'type': 'item', 'key': 'minecraft:diamond', 'count': 1}]}, opener=browser)
    assert order[0] == 200
    order_id = order[1]['order']['id']
    assert agent(network, key, '/api/v1/orders/claim', {'server': fake.SERVER, 'stash': fake.STASH, 'player': fake.PLAYER})['body']['order']['id'] == order_id
    ok('external agent container: signed index, heartbeat and order/claim; invalid signatures and Bearer rejected')
    digest = run(['docker', 'exec', container, 'sha256sum', '/var/lib/wesort/config.properties']).stdout
    second = run(['docker', 'run', '--rm', '--network', 'none', '--mount', f'type=volume,src={volume},dst=/var/lib/wesort', args.image], check=False)
    assert second.returncode != 0
    assert run(['docker', 'exec', container, 'sha256sum', '/var/lib/wesort/config.properties']).stdout == digest
    run(compose + ['restart', 'manager'])
    wait_ready(container)
    assert run(['docker', 'exec', container, 'sha256sum', '/var/lib/wesort/config.properties']).stdout == digest
    assert http('/api/v1/auth/login', {'password': password}, opener=browser)[0] == 200
    assert len(http('/api/v1/stashes', opener=browser)[1]) == 1
    assert order_id in http('/api/v1/orders?server=' + fake.SERVER + '&stash=' + fake.STASH, opener=browser)[3].decode()
    ok('single-process volume lock and SIGTERM/restart preserve keys, Owner, sessions, index, order and audit')
    with tempfile.TemporaryDirectory(prefix='wesort-tls-') as temporary:
        tmp = Path(temporary)
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=wesort.test',
            '-addext', 'subjectAltName=DNS:wesort.test,IP:127.0.0.1', '-keyout', str(tmp / 'server.key'), '-out', str(tmp / 'server.crt')])
        (tmp / 'server.key').chmod(0o600)
        run(['docker', 'run', '-d', '--rm', '--name', proxy_name, '--network', network, '-p', '127.0.0.1::4443',
            '--mount', f'type=bind,src={tmp},dst=/cert,readonly', '--mount',
            f'type=bind,src={repo / "docker/tls-test-proxy.py"},dst=/proxy.py,readonly', python_image, 'python', '/proxy.py'])
        proxy_info = json.loads(run(['docker', 'inspect', proxy_name]).stdout)[0]
        proxy_ip = proxy_info['NetworkSettings']['Networks'][network]['IPAddress']
        port = proxy_info['NetworkSettings']['Ports']['4443/tcp'][0]['HostPort']
        override = tmp / 'override.json'
        override.write_text(json.dumps({'services': {'manager': {'environment': {
            'WESORT_ALLOWED_HOSTS': 'wesort.test', 'WESORT_TRUSTED_PROXIES': proxy_ip, 'WESORT_SECURE_COOKIES': 'true'}}}}))
        run(compose + ['-f', str(override), 'up', '-d', '--no-build', '--force-recreate', 'manager'])
        container = run(compose + ['ps', '-q', 'manager']).stdout.strip()
        wait_ready(container)
        base = 'https://127.0.0.1:' + port
        tls_cookies = CookieJar()
        browser = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(tmp / 'server.crt'))),
            urllib.request.HTTPCookieProcessor(tls_cookies))
        assert http('/api/v1/auth/login', {'password': password}, opener=browser, host='wesort.test', origin='https://evil.invalid')[0] == 403
        response = http('/api/v1/auth/login', {'password': password}, opener=browser, host='wesort.test', origin='https://wesort.test',
            extra={'X-Forwarded-For': '203.0.113.99'})
        assert response[0] == 200
        assert 'Secure' in response[2].get('Set-Cookie', '')
        assert http('/api/v1/stashes', opener=browser, host='wesort.test')[0] == 200
        audit = http('/api/v1/audit', opener=browser, host='wesort.test')
        assert audit[0] == 200 and b'203.0.113.99' not in audit[3]
        assert run(['docker', 'exec', container, 'sha256sum', '/var/lib/wesort/config.properties']).stdout == digest
        ok('real TLS reverse proxy: configured Host/Origin, Secure cookie, spoofed forwarding headers scrubbed, volume upgrade preserved')
    report = {'image': args.image, 'platform': 'linux/amd64', 'checks': checks, 'result': 'PASS',
        'limits': 'Protocol agent and HTTP/DOM bootstrap tested; no real Minecraft account or physical delivery tested.'}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
finally:
    run(['docker', 'rm', '-f', proxy_name], check=False)
    run(compose + ['down', '-v', '--remove-orphans'], check=False)
