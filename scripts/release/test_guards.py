#!/usr/bin/env python3
"""Negative tests for publication gates; they never log in or contact a registry."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHA = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()

class Guards(unittest.TestCase):
    def metadata(self, **changes):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'output'
            env = dict(os.environ, GITHUB_SHA=SHA, GITHUB_REF='refs/heads/main', GITHUB_EVENT_NAME='workflow_dispatch',
                GITHUB_REPOSITORY='Crispy-Monke-eSports/WeSort', PUBLISH_REQUESTED='false', REQUESTED_VERSION='',
                GITHUB_OUTPUT=str(output))
            env.update(changes)
            result = subprocess.run(['python3', str(ROOT / 'scripts/release/metadata.py')], cwd=ROOT,
                env=env, text=True, capture_output=True)
            return result.returncode, output.read_text() if output.exists() else ''

    def test_pull_request_cannot_enable_publishing(self):
        code, output = self.metadata(GITHUB_REF='refs/pull/42/merge', GITHUB_EVENT_NAME='pull_request', PUBLISH_REQUESTED='true')
        self.assertEqual(code, 0)
        self.assertIn('publish=false', output)

    def test_manual_dry_run_does_not_publish(self):
        code, output = self.metadata()
        self.assertEqual(code, 0)
        self.assertIn('publish=false', output)

    def test_feature_branch_cannot_publish(self):
        code, output = self.metadata(GITHUB_REF='refs/heads/feature', PUBLISH_REQUESTED='true')
        self.assertNotEqual(code, 0)
        self.assertNotIn('publish=true', output)

    def test_fork_cannot_publish(self):
        code, output = self.metadata(GITHUB_REPOSITORY='someone/WeSort', PUBLISH_REQUESTED='true')
        self.assertNotEqual(code, 0)
        self.assertNotIn('publish=true', output)

    def test_version_override_cannot_differ_from_application(self):
        code, _ = self.metadata(REQUESTED_VERSION='v9.9.9')
        self.assertNotEqual(code, 0)

    def test_tag_must_match_application_version(self):
        code, _ = self.metadata(GITHUB_REF='refs/tags/v9.9.9', GITHUB_EVENT_NAME='push')
        self.assertNotEqual(code, 0)

    def test_wrong_source_sha_is_refused(self):
        code, _ = self.metadata(GITHUB_SHA='1' * 40)
        self.assertNotEqual(code, 0)

    def test_corrupt_candidate_is_rejected_before_docker_or_registry(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            release = root / 'build/release'
            release.mkdir(parents=True)
            (release / 'manager.tar').write_text('tampered archive')
            (release / 'candidate.json').write_text(json.dumps({'revision': SHA, 'version': 'v0.1.0-dev.0', 'archive_sha256': '0' * 64}))
            executable = root / 'docker'
            executable.write_text('#!/bin/sh\ntouch "' + str(root / 'unexpected-docker') + '"\nexit 99\n')
            executable.chmod(0o700)
            env = dict(os.environ, GITHUB_SHA=SHA, PATH=str(root) + ':' + os.environ['PATH'])
            env.pop('GITHUB_TOKEN', None)
            result = subprocess.run(['python3', str(ROOT / 'scripts/release/publish.py')], cwd=root, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / 'unexpected-docker').exists())

if __name__ == '__main__': unittest.main()
