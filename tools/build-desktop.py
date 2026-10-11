#!/usr/bin/env python3
"""Build a desktop image and its signed release archive on the target operating system."""
import argparse
import importlib.util
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=Path(os.environ.get('SPOTYGRAM_STATE',
        Path.home() / '.local/state/spotygram')))
    parser.add_argument('--image-only', action='store_true', help='Build without signing a release archive')
    parser.add_argument('--debug', action='store_true', help='Allow the isolated Telegram test environment')
    args = parser.parse_args()
    state = args.state.resolve()
    if state == ROOT or ROOT in state.parents:
        parser.error('Build state must be outside the source checkout')
    state.mkdir(parents=True, exist_ok=True)
    target_os = {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}[platform.system()]
    target_arch = 'arm64' if platform.machine().lower() in ('aarch64', 'arm64') else 'x64'
    target = f'{target_os}-{target_arch}'
    if target not in ('windows-x64', 'linux-x64', 'macos-x64', 'macos-arm64'):
        parser.error(f'No pinned native dependencies for {target}')
    version = re.search(r'^version = "([0-9]+\.[0-9]+\.[0-9]+)"$',
                        (ROOT / 'build.gradle.kts').read_text(), re.MULTILINE).group(1)
    environment = dict(os.environ, SPOTYGRAM_STATE=str(state))
    wrapper = ROOT / ('gradlew.bat' if target_os == 'windows' else 'gradlew')
    command = [str(wrapper), '--project-cache-dir', str(state / 'gradle'),
        f'-Pkotlin.project.persistent.dir={state / "kotlin"}',
        '-Pkotlin.compiler.execution.strategy=in-process', '-PdesktopOnly=true',
        f'-PdesktopDebug={str(args.debug).lower()}', '--max-workers=2',
        ':desktop:createDistributable', '--console=plain', '--no-daemon']
    if target_os == 'windows':
        command = ['cmd.exe', '/d', '/c', *command]
    try:
        subprocess.run(command, cwd=ROOT, env=environment, check=True)
    except subprocess.CalledProcessError as error:
        for log in sorted((state / 'build/desktop/compose/logs').rglob('*-err.txt')):
            print(log.read_text(errors='replace'), file=sys.stderr)
        raise SystemExit(error.returncode)
    image = state / 'build/desktop/distributions/main/app' / ('Spotygram.app' if target_os == 'macos' else 'Spotygram')
    if args.image_only:
        print(image)
        return
    if args.debug:
        parser.error('Debug images cannot be published to the release update channel')
    secret = os.environ.get('DESKTOP_RELEASE_KEY')
    if secret:
        private = state / 'desktop/signing/release-private.pem'
        private.parent.mkdir(parents=True, exist_ok=True)
        if private.exists() and private.read_text().strip() != secret.strip():
            raise RuntimeError('Refusing to replace an existing desktop release key')
        private.write_text(secret)
        private.chmod(0o600)
    spec = importlib.util.spec_from_file_location('package_desktop', ROOT / 'tools/package-desktop.py')
    packaging = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(packaging)
    packaging.package(image, target, version, state, state / 'releases' / version)


if __name__ == '__main__':
    main()
