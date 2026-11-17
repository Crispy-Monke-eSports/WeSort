#!/usr/bin/env python3
"""Resolve the original Meteor dependency offline with a genuinely empty dependency cache."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = '26.3-20261115.173242-22'


def quoted(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"


def project(folder, vendor):
    folder.mkdir()
    (folder / 'settings.gradle').write_text("rootProject.name = 'frozen-meteor-offline-proof'\n")
    (folder / 'build.gradle').write_text("plugins { id 'java' }\n"
        + 'ext.wesortVendorRoot = file(' + quoted(vendor) + ')\n'
        + 'configurations { mainRuntimeDependencyResolve { transitive = false } }\n'
        + 'apply from: ' + quoted(ROOT / 'gradle/frozen-meteor.gradle') + '\n'
        + "dependencies { mainRuntimeDependencyResolve 'meteordevelopment:meteor-client:" + VERSION + "' }\n")


def main():
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    pattern = 'wrapper/dists/gradle-9.6.0-bin/*/gradle-9.6.0/bin/gradle'
    executables = list(home.glob(pattern))
    if not executables:
        # Bootstrap Gradle itself separately; the dependency proof below is strictly offline.
        subprocess.run([str(ROOT / 'gradlew'), '--version'], cwd=ROOT, check=True, capture_output=True)
        executables = list(home.glob(pattern))
    if not executables:
        raise RuntimeError('Gradle 9.6.0 distribution was not installed')
    with tempfile.TemporaryDirectory(prefix='wesort-frozen-meteor-') as raw:
        folder = Path(raw)
        valid = folder / 'valid'
        project(valid, ROOT / 'vendor')
        cache = folder / 'empty-cache'
        assert not cache.exists()
        command = [str(executables[0]), '--no-daemon', '--offline', '--gradle-user-home', str(cache), '-p', str(valid), 'check']
        passed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        if passed.returncode or 'Verified original Meteor 26.3-22 runtime SHA-256' not in passed.stdout:
            raise RuntimeError('Cold offline Meteor resolution failed:\n' + passed.stdout + passed.stderr)
        corrupted = folder / 'tampered-vendor'
        shutil.copytree(ROOT / 'vendor', corrupted)
        jar = next((corrupted / 'maven').rglob('*.jar'))
        with jar.open('r+b') as file:
            first = file.read(1)
            file.seek(0)
            file.write(bytes([first[0] ^ 1]))
        invalid = folder / 'invalid'
        project(invalid, corrupted)
        command[command.index('-p') + 1] = str(invalid)
        rejected = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        if rejected.returncode == 0 or 'Frozen Meteor checksum mismatch' not in rejected.stdout + rejected.stderr:
            raise RuntimeError('A modified Meteor binary was not rejected before dependency use')
    output = ROOT / 'build/evidence/mc26'
    output.mkdir(parents=True, exist_ok=True)
    result = {'result': 'PASS', 'version': VERSION, 'runtime': '26.3-22',
        'checks': ['original artifact resolved with empty dependency cache and --offline',
                   'modified binary rejected before use', 'corresponding source archive and GPL text checksum verified']}
    (output / 'frozen-meteor-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: frozen Meteor 26.3-22 resolves offline without dependency cache; tampering is rejected')


if __name__ == '__main__':
    main()
