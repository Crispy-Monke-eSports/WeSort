#!/usr/bin/env python3
"""Require a fresh successful real WeSort client run, including an error-free GUI."""
import json
from pathlib import Path
import re
import subprocess
import time

root = Path(__file__).resolve().parents[2]
report = root / 'build/run-26.3/runtime-result.json'
report.unlink(missing_ok=True)
evidence = root / 'build/evidence/mc26'
evidence.mkdir(parents=True, exist_ok=True)
logfile = evidence / 'client-runtime.log'
# Maven Central occasionally returns 429 while concurrent Minecraft matrices fetch
# Gradle plugins. Retry *only* dependency throttling, not a failed client/probe.
command = [str(root / 'gradlew'), '--no-daemon', 'build', 'runClient',
    '-Pmc=26.3', '-PwithBaritone=true', '-PruntimeProbe=true']
attempt_logs = []
for attempt in range(1, 4):
    attempt_log = evidence / f'client-runtime-attempt-{attempt}.log'
    attempt_logs.append(attempt_log)
    with attempt_log.open('w') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
            timeout=600, check=False)
    logfile.write_text('\n'.join(p.read_text() for p in attempt_logs))
    if result.returncode == 0:
        break
    if 'Received status code 429' not in attempt_log.read_text() or attempt == 3:
        raise subprocess.CalledProcessError(result.returncode, command)
    time.sleep(15 * attempt)
if re.search(r'\[(?:Render thread|main)/ERROR\].*(?:WeSortAddon|Mixin|wesort)', logfile.read_text()):
    raise RuntimeError('The client logged an error; inspect client-runtime.log')
result = json.loads(report.read_text())
expected = {'minecraft': '26.3', 'fabricloader': '0.19.5', 'meteor-client': '26.3-22',
    'baritone-meteor': '1.4.0-26.3', 'result': 'PASS'}
for key, value in expected.items():
    if result.get(key) != value:
        raise RuntimeError(f'Unexpected {key}: {result.get(key)}')
if result.get('wesort') != '0.1.0-dev.0+26.3' or not result.get('java', '').startswith('25.0.5+8'):
    raise RuntimeError('Unexpected WeSort or Java runtime')
(evidence / 'client-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
