#!/usr/bin/env python3
"""Bind the tested Docker archive and SBOM to the source and expected OCI identity."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
folder = Path('build/release')

def content_digest(image):
    content = {key: image[key] for key in ('Os', 'Architecture', 'Config', 'RootFS')}
    return hashlib.sha256(json.dumps(content, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', 'wesort-manager:candidate'], text=True))[0]
labels = info['Config']['Labels']
if not labels['org.opencontainers.image.revision'] == os.environ['GITHUB_SHA']:
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.version'] == os.environ['APP_VERSION']:
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.source'] == 'https://github.com/Crispy-Monke-eSports/WeSort':
    raise RuntimeError('Release integrity check failed')
if not labels['org.opencontainers.image.licenses'] == 'GPL-3.0':
    raise RuntimeError('Release integrity check failed')
if not (info['Os'] == 'linux' and info['Architecture'] == 'amd64'):
    raise RuntimeError('Release integrity check failed')
if not info['Config']['User'] == '10001:10001':
    raise RuntimeError('Release integrity check failed')
if not json.loads((folder / 'docker-smoke.json').read_text())['result'] == 'PASS':
    raise RuntimeError('Release integrity check failed')
if not json.loads((folder / 'manager.spdx.json').read_text())['spdxVersion'].startswith('SPDX-'):
    raise RuntimeError('Release integrity check failed')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as src:
        for part in iter(lambda: src.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()
metadata = {'repository': os.environ['GITHUB_REPOSITORY'], 'revision': os.environ['GITHUB_SHA'], 'version': os.environ['IMAGE_VERSION'], 'image_id': info['Id'], 'image_content_sha256': content_digest(info), 'platform': 'linux/amd64', 'workflow_run': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}", 'archive_sha256': digest(folder / 'manager.tar'), 'sbom_sha256': digest(folder / 'manager.spdx.json'), 'provenance': 'Unsigned CI metadata plus Actions artifact integrity; not a cryptographic SLSA attestation.'}
(folder / 'candidate.json').write_text(json.dumps(metadata, indent=2) + '\n')
print('Tested candidate identity, labels, archive, SPDX and source verified.')
