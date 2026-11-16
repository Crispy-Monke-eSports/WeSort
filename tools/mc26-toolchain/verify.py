#!/usr/bin/env python3
"""Run a real isolated client; stale reports and a successful compile cannot pass."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
report = root / 'build/probe-run/toolchain-result.json'
report.unlink(missing_ok=True)
evidence = root / 'build/evidence'
evidence.mkdir(parents=True, exist_ok=True)
with (evidence / 'runtime.log').open('w') as log:
    subprocess.run([str(root / 'gradlew'), '--no-daemon', 'build', 'runClient'],
        cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=True)
result = json.loads(report.read_text())
expected = {'minecraft': '26.3', 'fabricloader': '0.19.5', 'meteor-client': '26.3-22',
    'baritone-meteor': '1.4.0-26.3', 'result': 'PASS'}
for key, value in expected.items():
    if result.get(key) != value:
        raise RuntimeError(f'Unexpected {key}: {result.get(key)}')
if not result['java'].startswith('25.0.5+8'):
    raise RuntimeError('Use the pinned Temurin 25.0.5+8 runtime')
(evidence / 'runtime.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
