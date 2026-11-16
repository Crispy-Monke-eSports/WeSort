#!/usr/bin/env python3
"""Generate/check vanilla 26.3 metadata using a real connected client with bound registries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path

spec = importlib.util.spec_from_file_location('world_harness', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)


class MetadataHarness(world.Harness):
    def execute(self, write):
        try:
            self.start_server(); self.fixture(); self.start_client()
            jar = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/fabric-loom/26.3/minecraft-client.jar'
            world.require(hashlib.sha1(jar.read_bytes()).hexdigest() == 'e877b6a07acd633fb3bb475002175cec036e7b87', 'Not the pinned official 26.3 client jar')
            self.command('export-resources', jar=str(jar), timeout=60)
            generated = self.control / 'resources'
            world.shutil.copytree(generated, self.evidence / 'resources')
            targets = {'creative-tabs.json': 'stash-manager-server/src/main/resources/creative-tabs/26.3.json',
                'recipes.json': 'src/main/resources/assets/wesort/recipes/26.3.json'}
            for name, target in targets.items():
                value = json.loads((generated / name).read_text())
                world.require(value['version'] == '26.3', 'Wrong exported metadata version')
                destination = world.ROOT / target
                if write: destination.write_bytes((generated / name).read_bytes())
                else: world.require(json.loads(destination.read_text()) == value, 'Committed metadata differs from actual game: ' + name)
            tabs = json.loads((generated / 'creative-tabs.json').read_text())
            recipes = json.loads((generated / 'recipes.json').read_text())
            world.require(len(tabs['items']) > 1400 and len(recipes['recipes']) > 800, 'Export unexpectedly incomplete')
            self.ok('actual vanilla 26.3 creative categories and crafting recipes exported from bound registries, checked against repository resources')
            result = {'result': 'PASS', 'source_client_sha1': hashlib.sha1(jar.read_bytes()).hexdigest(),
                'creative_items': len(tabs['items']), 'crafting_recipes': len(recipes['recipes']), 'checks': self.checks}
            (self.evidence / 'metadata-result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally: self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-resources', action='store_true', help='Explicitly replace generated WeSort resources; default only compares')
    MetadataHarness(world.DEFAULT_IMAGE).execute(parser.parse_args().write_resources)
