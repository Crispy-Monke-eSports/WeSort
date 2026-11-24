#!/usr/bin/env python3
"""Require a fresh successful real WeSort client run, including an error-free GUI."""
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
report = root / 'build/run-26.3/runtime-result.json'
report.unlink(missing_ok=True)
evidence = root / 'build/evidence/mc26'
evidence.mkdir(parents=True, exist_ok=True)
logfile = evidence / 'client-runtime.log'
with logfile.open('w') as log:
    try:
        subprocess.run([str(root / 'gradlew'), '--no-daemon', 'assemble', 'runClient',
            '-Pmc=26.3', '-PwithBaritone=true', '-PruntimeProbe=true'],
            cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=True)
    except subprocess.CalledProcessError as failed:
        # The probe always stops the client on failure, so a non-zero exit means
        # the probe itself failed: surface its FAIL payload, not a Gradle traceback.
        try:
            failed_result = json.loads(report.read_text())
        except OSError:
            failed_result = {}
        raise RuntimeError('Client runtime probe failed during run: %s' % (
            failed_result.get('error', 'exit %s; inspect client-runtime.log' % failed.returncode)))
    except subprocess.TimeoutExpired:
        # Stage diagnostics instead of a bare timeout: the probe now always stops
        # the client on failure, so a timeout means a startup, render or shutdown
        # hang. The markers below say which one.
        text = logfile.read_text()
        if 'WESORT_CLIENT_RUNTIME_PASS' in text or 'WESORT_CLIENT_RUNTIME_FAIL' in text:
            stage = 'shutdown hang: probe finished but the client did not exit'
        elif 'WESORT_CLIENT_RUNTIME_PROBE_CHECK' in text:
            stage = 'probe hang: window check never completed'
        elif 'WESORT_CLIENT_RUNTIME_PROBE_OPEN' in text:
            stage = 'probe hang: module/window open never completed'
        elif 'WESORT_CLIENT_RUNTIME_START' in text:
            stage = 'startup hang: probe thread never started (Mixin/client init stuck)'
        else:
            stage = 'startup hang: client never initialized (Gradle/Minecraft boot stuck)'
        raise RuntimeError('Client runtime %s; inspect client-runtime.log' % stage)
if re.search(r'\[(?:Render thread|main)/ERROR\].*(?:WeSortAddon|Mixin|wesort)', logfile.read_text()):
    raise RuntimeError('The client logged an error; inspect client-runtime.log')
result = json.loads(report.read_text())
if result.get('result') != 'PASS':
    raise RuntimeError('Client runtime probe reported failure: %s' % result.get('error', result))
expected = {'minecraft': '26.3', 'fabricloader': '0.19.5', 'meteor-client': '26.3-22',
    'baritone-meteor': '1.4.0-26.3', 'result': 'PASS'}
for key, value in expected.items():
    if result.get(key) != value:
        raise RuntimeError(f'Unexpected {key}: {result.get(key)}')
if result.get('wesort') != '0.1.0-dev.0+26.3' or not result.get('java', '').startswith('25.0.5+8'):
    raise RuntimeError('Unexpected WeSort or Java runtime')
(evidence / 'client-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
