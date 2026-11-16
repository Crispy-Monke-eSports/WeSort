#!/usr/bin/env python3
"""Actual Baritone routes and fail-closed navigation in a fresh private 26.3 world."""
import argparse
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('world_harness', Path(__file__).with_name('world-harness.py'))
world = importlib.util.module_from_spec(spec); spec.loader.exec_module(world)
require = world.require


class NavigationHarness(world.Harness):
    def fixture(self):
        super().fixture()
        for command in [
            'fill 32 64 -1 32 64 1 minecraft:smooth_stone',
            'fill 33 64 -1 33 65 1 minecraft:smooth_stone',
            'fill 34 64 -1 38 66 4 minecraft:smooth_stone',
            'setblock 36 67 3 minecraft:barrel',
            'fill 34 64 -7 38 67 -3 minecraft:glass',
            'fill 35 64 -6 37 66 -4 minecraft:air',
            'forceload add 1000 -16 1055 15',
        ]: self.rcon.command(command)
        for _ in range(100):
            reply = self.rcon.command('execute if loaded 1048 70 12 run time query gametime')
            if 'time is ' in reply: break
            time.sleep(0.1)
        else: raise RuntimeError('Reference chunks did not load')
        reply = self.rcon.command('clone -8 63 -8 40 70 12 1000 63 -8 replace force')
        require('cloned' in reply, 'Server rejected world reference: ' + reply)

    def state(self): return self.command('state')['state']

    def settings(self, state):
        return {key: state[key] for key in ['allowBreak', 'allowPlace', 'allowParkour', 'allowWaterBucketFall', 'maxFallHeightNoWater']}

    def start_walk(self, x, y=64, z=0, reach=False):
        response = self.command('navigate', x=x, y=y, z=z, reach=reach)
        state = response['state']
        require(not state['allowBreak'] and not state['allowPlace'] and not state['allowParkour'], 'Actual Baritone settings were not confined')
        return state

    def wait_walk(self, timeout=70):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            state = self.state()
            if state['navigation'] != 'RUNNING': return state
            time.sleep(0.2)
        raise RuntimeError('Navigation never reported a bounded outcome')

    def stable(self):
        time.sleep(0.5)
        first = self.state(); time.sleep(0.5); second = self.state()
        require(not second['pathing'], 'A released WeSort path remained active')
        require(abs(first['x'] - second['x']) + abs(first['z'] - second['z']) < 0.15, 'Player kept moving after cancellation')
        require(self.settings(second) == self.original_settings, 'Baritone user settings were not restored')
        return second

    def tour(self):
        self.teleport(0.5, 0.5)
        self.original_settings = self.settings(self.state())
        route = []
        for x in [0, 9, 18, 27]:
            self.start_walk(x, 64, 3, True)
            state = self.wait_walk()
            require(state['navigation'] == 'ARRIVED', 'Container route failed: ' + state['navigation'])
            opened = self.command('open', x=x, y=64, z=3, slots=54, item='minecraft:diamond', count=64, second=x+1)
            self.command('close'); self.stable(); route.append(opened)
        self.start_walk(35, 67, 0)
        state = self.wait_walk()
        require(state['navigation'] == 'ARRIVED' and state['y'] >= 66.9, 'Baritone did not climb the real stairs')
        self.command('open', x=36, y=67, z=3, slots=27, item='minecraft:air', count=0, capture=False); self.command('close')
        for x, z, item, count in [(3, -3, 'minecraft:dirt', 16), (0, -3, 'minecraft:diamond', 32)]:
            self.start_walk(x, 64, z, True)
            require(self.wait_walk()['navigation'] == 'ARRIVED', 'Barrel/shulker route failed')
            self.command('open', x=x, y=64, z=z, slots=27, item=item, count=count); self.command('close')
        self.start_walk(0, 64, 0)
        require(self.wait_walk()['navigation'] == 'ARRIVED', 'Return to workspace failed')
        self.stable()
        (self.evidence / 'route.json').write_text(json.dumps(route, indent=2) + '\n')
        self.ok('actual Baritone round trip between double chests across loaded x=16 chunk boundary, barrel, shulker, raised container and workspace')

    def fault_cases(self):
        # Target is outside the private server view distance; do not even start a path.
        rejected = self.command('navigate', x=2048, y=64, z=0, allow_failure=True)
        require(not rejected['success'] and 'Unloaded' in rejected['message'], 'Unloaded test target was accepted')
        self.start_walk(40)
        self.command('nav-cancel')
        state = self.stable()
        require(state['navigation'] == 'PAUSED_NAVIGATION_CANCELLED' and state['navigationPaused'], 'Cancel did not pause')
        require(not self.command('navigate', x=0, y=64, z=0, allow_failure=True)['success'], 'Paused route silently restarted')
        self.command('nav-resume')
        self.ok('explicit cancel stops movement, restores Baritone settings and requires explicit resume; unloaded target refused')

        self.teleport(36.5, -5.5)
        self.start_walk(40, 64, -5)
        state = self.wait_walk(20)
        require(state['navigationPaused'] and state['navigation'] != 'ARRIVED', 'Unreachable glass enclosure was reported reached')
        self.stable(); self.command('nav-resume')
        self.ok('real unreachable enclosure produces bounded pause: ' + state['navigation'])

        # Server-authoritative displacement keeps the player moving, but prevents arrival.
        # This exercises the real absolute 60-second deadline rather than a shortened test-only clock.
        self.teleport(0.5, 0.5); self.start_walk(40)
        started = time.monotonic(); iteration = 0
        while time.monotonic() - started < 70:
            state = self.state()
            if state['navigation'] != 'RUNNING': break
            self.rcon.command(f'tp {self.player} {0.5 + iteration % 2} 64 0.5'); iteration += 1
            time.sleep(1.0)
        require(state['navigation'] == 'PAUSED_NAVIGATION_TIMEOUT', 'Absolute timeout was not exercised: ' + state['navigation'])
        self.stable(); self.command('nav-resume')
        self.ok('real 60-second absolute timeout despite continued displacement stops path and restores settings')

        self.rcon.command('execute in minecraft:the_nether run forceload add 0 0')
        for _ in range(100):
            if 'time is ' in self.rcon.command('execute in minecraft:the_nether if loaded 0 119 0 run time query gametime'): break
            time.sleep(0.1)
        else: raise RuntimeError('Private dimension fixture did not load')
        self.rcon.command('execute in minecraft:the_nether run fill 0 119 0 3 119 3 minecraft:obsidian')
        self.rcon.command('execute in minecraft:the_nether run fill 0 120 0 3 123 3 minecraft:air')
        self.teleport(0.5, 0.5); self.start_walk(40)
        reply = self.rcon.command(f'execute in minecraft:the_nether run tp {self.player} 0 120 0')
        require('Teleported' in reply, 'Private dimension change rejected')
        state = self.wait_walk(20)
        require(state['navigationPaused'] and state['navigation'] in ['PAUSED_WRONG_DIMENSION', 'PAUSED_NAVIGATION_DISCONNECTED'],
            'Dimension change did not pause: ' + state['navigation'])
        self.stable()
        self.rcon.command(f'execute in minecraft:overworld run tp {self.player} 0.5 64 0.5')
        for _ in range(100):
            if self.state()['dimension'] == 'minecraft:overworld': break
            time.sleep(0.1)
        self.command('nav-resume')
        self.ok('real server dimension change stops the old route without automatic continuation')

    def execute(self):
        try:
            self.start_server(); self.fixture(); before = self.physical_snapshot()
            self.start_manager(); self.start_client()
            index = self.observe(); self.verify_manager_index(index)
            self.tour(); self.fault_cases()
            reply = self.rcon.command('execute if blocks -8 63 -8 40 70 12 1000 63 -8 all run time query gametime')
            require('time is ' in reply and before == self.physical_snapshot(), 'Navigation modified the reference warehouse blocks or inventory')
            self.ok('server compares every warehouse block and block entity with an independent pre-navigation clone; inventory totals/components unchanged')
            self.command('screenshot')
            world.shutil.copyfile(self.game_dir / 'screenshots/fixture.png', self.evidence / 'fixture.png')
            result = {'result': 'PASS', 'scope': 'W-016 real Baritone navigation on Minecraft 26.3', 'checks': self.checks,
                'source_commit': world.subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=world.ROOT, text=True).strip(),
                'dirty_worktree': bool(world.subprocess.check_output(['git', 'status', '--porcelain'], cwd=world.ROOT, text=True).strip()),
                'limits': 'Local warehouse only; global transport, portals and multiagent routing remain W-036.'}
            (self.evidence / 'navigation-result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('Evidence: ' + str(self.evidence), flush=True)
        finally: self.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager-image', default=world.DEFAULT_IMAGE)
    NavigationHarness(parser.parse_args().manager_image).execute()
