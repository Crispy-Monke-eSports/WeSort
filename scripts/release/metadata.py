#!/usr/bin/env python3
"""Validate release provenance before giving any job package-write permission."""
import os
from pathlib import Path
import re
import subprocess
root = Path(__file__).resolve().parents[2]
text = (root / 'stash-manager-server/build.gradle.kts').read_text()
version = re.search('^version = "([^"]+)"$', text, re.M).group(1)
tag = 'v' + version
pattern = 'v\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?'
if not re.fullmatch(pattern, tag):
    raise RuntimeError('Invalid application version')
sha = os.environ['GITHUB_SHA']
if not re.fullmatch('[0-9a-f]{40}', sha):
    raise RuntimeError('Release integrity check failed')
if not subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == sha:
    raise RuntimeError('Release integrity check failed')
ref = os.environ['GITHUB_REF']
event = os.environ['GITHUB_EVENT_NAME']
requested = os.environ.get('REQUESTED_VERSION', '')
if requested:
    if not requested == tag:
        raise RuntimeError('Requested OCI tag must match the application version')
if ref.startswith('refs/tags/'):
    if not ref == 'refs/tags/' + tag:
        raise RuntimeError('Git tag must match application version')
publish = ref.startswith('refs/tags/v') or (event == 'workflow_dispatch' and os.environ.get('PUBLISH_REQUESTED') == 'true')
if publish:
    if not os.environ['GITHUB_REPOSITORY'].lower() == 'crispy-monke-esports/wesort':
        raise RuntimeError('Release integrity check failed')
    if not (ref == 'refs/heads/main' or ref.startswith('refs/tags/v')):
        raise RuntimeError('Only main or a release tag may publish')
    subprocess.run(['git', 'fetch', '--no-tags', 'origin', 'main'], check=True)
    subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], check=True)
with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
    out.write(f'version={tag}\napp_version={version}\npublish={str(publish).lower()}\n')
print(f'Validated source {sha}, version {tag}, publish={publish}; latest is never assigned here.')
