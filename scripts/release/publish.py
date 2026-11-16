#!/usr/bin/env python3
"""Publish exactly the tested archive; never replace a tag belonging to a different image."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
registry = 'ghcr.io/crispy-monke-esports/wesort-manager'
source = 'https://github.com/Crispy-Monke-eSports/WeSort'
folder = Path('build/release')

def content_digest(image):
    content = {key: image[key] for key in ('Os', 'Architecture', 'Config', 'RootFS')}
    return hashlib.sha256(json.dumps(content, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
metadata = json.loads((folder / 'candidate.json').read_text())
sha = os.environ['GITHUB_SHA']
version = metadata['version']
if not (metadata['revision'] == sha and re.fullmatch('[0-9a-f]{40}', sha)):
    raise RuntimeError('Release integrity check failed')
if not re.fullmatch('v\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?', version):
    raise RuntimeError('Release integrity check failed')

def checksum(path):
    h = hashlib.sha256()
    with path.open('rb') as src:
        for part in iter(lambda: src.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()
if not checksum(folder / 'manager.tar') == metadata['archive_sha256']:
    raise RuntimeError('Release integrity check failed')
if not checksum(folder / 'manager.spdx.json') == metadata['sbom_sha256']:
    raise RuntimeError('Release integrity check failed')
subprocess.run(['docker', 'load', '-i', str(folder / 'manager.tar')], check=True)
info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', 'wesort-manager:candidate'], text=True))[0]
if not content_digest(info) == metadata['image_content_sha256']:
    raise RuntimeError('Release integrity check failed')
labels = info['Config']['Labels']
if not labels['org.opencontainers.image.source'] == source:
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.revision'] == sha:
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.version'] == version[1:]:
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.licenses'] == 'GPL-3.0':
    raise RuntimeError('Release integrity check failed')
if not (info['Os'] == 'linux' and info['Architecture'] == 'amd64'):
    raise RuntimeError('Release integrity check failed')

def api(path):
    request = urllib.request.Request('https://api.github.com' + path, headers={'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'], 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise RuntimeError(f'Package API returned HTTP {e.code}') from None
package_path = '/orgs/Crispy-Monke-eSports/packages/container/wesort-manager'
existing = set()
page = 1
while True:
    versions = api(package_path + f'/versions?per_page=100&page={page}')
    if not versions:
        break
    for entry in versions:
        existing.update(entry.get('metadata', {}).get('container', {}).get('tags', []))
    if len(versions) < 100:
        break
    page += 1
for tag in (version, 'sha-' + sha):
    target = registry + ':' + tag
    if tag in existing:
        subprocess.run(['docker', 'pull', target], check=True)
        old = json.loads(subprocess.check_output(['docker', 'image', 'inspect', target], text=True))[0]
        if content_digest(old) != metadata['image_content_sha256']:
            raise RuntimeError(f'Refusing to overwrite immutable tag {tag}')
        print(f'Existing identical tag retained: {tag}')
    else:
        subprocess.run(['docker', 'tag', 'wesort-manager:candidate', target], check=True)
        subprocess.run(['docker', 'push', target], check=True)
subprocess.run(['docker', 'pull', registry + ':' + version], check=True)
pushed = json.loads(subprocess.check_output(['docker', 'image', 'inspect', registry + ':' + version], text=True))[0]
if not content_digest(pushed) == metadata['image_content_sha256']:
    raise RuntimeError('Release integrity check failed')
references = [x for x in pushed['RepoDigests'] if x.startswith(registry + '@sha256:')]
if not references:
    raise RuntimeError('Release integrity check failed')
reference = references[0]
package = None
for attempt in range(30):
    package = api(package_path)
    if package and package.get('repository'):
        break
    time.sleep(2)
if not (package and package.get('repository', {}).get('full_name', '').lower() == 'crispy-monke-esports/wesort'):
    raise RuntimeError('Package is not linked to source repo')
report = dict(metadata, registry_reference=reference, tags=[version, 'sha-' + sha], package_url='https://github.com/orgs/Crispy-Monke-eSports/packages/container/package/wesort-manager', visibility=package['visibility'], source_association=package['repository']['full_name'])
(folder / 'publication.json').write_text(json.dumps(report, indent=2) + '\n')
with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
    out.write('reference=' + reference + '\n')
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
    out.write(f'Published `{reference}` as `{version}` and `sha-{sha}`.\n\n')
    out.write(f"Package source association verified. Visibility: **{package['visibility']}**.\n\n")
    if package['visibility'] != 'public':
        out.write('An organization package admin must set visibility to Public in package settings; then rerun only the anonymous pull job.\n')
print('Package pushed; immutable digest and repository association verified. Visibility=' + package['visibility'])
