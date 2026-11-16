"""Own offline build helpers. Python standard library; never download or edit the SDK."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
digest = lambda data: hashlib.sha256(data).hexdigest()

def parser(description):
    result = argparse.ArgumentParser(description=description)
    result.add_argument('--sdk-root', required=True, type=Path,
                        help='Read-only SDK containing the paths in dependencies.lock.json')
    result.add_argument('--java-home', required=True, type=Path, help='Installed Java 21 JDK')
    result.add_argument('--output', required=True, type=Path, help='Build directory outside repository and SDK')
    return result

def inside(child, parent):
    return child == parent or parent in child.parents

class Context:
    def __init__(self, args, fresh=True):
        self.sdk = args.sdk_root.resolve(strict=True)
        self.output = args.output.resolve()
        repository = ROOT.parents[1]
        if inside(self.output, repository) or inside(self.output, self.sdk):
            raise ValueError('Output must be outside the source repository and read-only SDK')
        if fresh and self.output.exists() and any(self.output.iterdir()):
            raise ValueError('Output directory must be new or empty')
        self.output.mkdir(parents=True, exist_ok=True)
        home = args.java_home.resolve(strict=True)
        suffix = '.exe' if os.name == 'nt' else ''
        self.java = home / 'bin' / ('java' + suffix)
        self.javac = home / 'bin' / ('javac' + suffix)
        self.java_version = self.run([self.java, '-version'], 'java-version')
        if not self.java_version.startswith(('openjdk version "21.', 'java version "21.')):
            raise ValueError('Java 21 is required')
        self.compiler_version = self.run([self.javac, '-version'], 'javac-version')
        if not self.compiler_version.startswith('javac 21.'):
            raise ValueError('Java 21 compiler is required')
        self.lock = json.loads((ROOT / 'dependencies.lock.json').read_text(encoding='utf-8'))
        self.deps = []
        for entry in self.lock['classpath']:
            path = (self.sdk / entry['path']).resolve(strict=True)
            if not inside(path, self.sdk):
                raise ValueError('Dependency escapes SDK')
            if digest(path.read_bytes()) != entry['sha256']:
                raise ValueError('Dependency SHA-256 mismatch: ' + entry['path'])
            self.deps.append(path)
        self.cp = os.pathsep.join(map(str, self.deps))

    def run(self, command, name, expected_failure=None):
        env = os.environ.copy()
        env['PYTHONDONTWRITEBYTECODE'] = '1'
        # Direct any JVM crash file to the external build directory as well.
        result = subprocess.run(list(map(str, command)), cwd=self.output, env=env,
                                capture_output=True, text=True, encoding='utf-8', errors='replace')
        log = result.stdout + result.stderr
        (self.output / (name + '.log')).write_text(log, encoding='utf-8')
        if expected_failure is not None:
            if result.returncode == 0 or expected_failure not in log:
                raise RuntimeError(name + ': expected baseline failure not reproduced\n' + log)
        elif result.returncode:
            raise RuntimeError(name + ' failed\n' + log)
        return log

    def compile(self, files, output, name, cp=None):
        output.mkdir(parents=True, exist_ok=True)
        options = ['--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp',
                   self.cp if cp is None else cp, '-d', str(output), *map(str, sorted(files))]
        argfile = self.output / (name + '.args')
        argfile.write_text('\n'.join('"' + value.replace('\\', '/') + '"' for value in options), encoding='utf-8')
        self.run([self.javac, '@' + str(argfile)], name)

    def check_inputs(self):
        for entry, path in zip(self.lock['classpath'], self.deps):
            if digest(path.read_bytes()) != entry['sha256']:
                raise ValueError('SDK input changed: ' + entry['path'])

    def artifact(self, name, classes, resources, license_name='LICENSE'):
        destination = self.output / name
        entries = {license_name: (ROOT / license_name).read_bytes()}
        for base in (classes, resources):
            for path in sorted(base.rglob('*')):
                if path.is_file():
                    entry = path.relative_to(base).as_posix()
                    if entry in entries and entries[entry] != path.read_bytes():
                        raise ValueError('Duplicate archive entry: ' + entry)
                    entries[entry] = path.read_bytes()
        if any(name.endswith('.class') and not name.startswith('org/motiquies/') for name in entries):
            raise ValueError('Third-party/test class in artifact')
        if any(name.startswith(('test/', 'net/', 'vazkii/', 'com/seibel/', 'mezz/')) for name in entries):
            raise ValueError('Third-party/test path in artifact')
        # STORE avoids compressor-version differences; order, time, flags and mode are fixed.
        with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_STORED) as archive:
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, (2026, 11, 16, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        with zipfile.ZipFile(destination) as archive:
            if archive.testzip() is not None:
                raise ValueError('Corrupt artifact')
        self.check_inputs()
        receipt = {
            'artifact': destination.name, 'sha256': digest(destination.read_bytes()),
            'entries': sorted(entries), 'dependencyLockSha256': digest((ROOT/'dependencies.lock.json').read_bytes()),
            'classpath': self.lock['classpath'], 'javaVersion': self.java_version.strip(),
            'javacVersion': self.compiler_version.strip(),
            'javacExecutableSha256': digest(self.javac.read_bytes()),
            'sourceFiles': {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(ROOT.rglob('*')) if p.is_file()},
            'sdkPreserved': True, 'gameExecuted': False, 'gameReleaseApproved': False,
        }
        (self.output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
        print(json.dumps({'artifact':str(destination),'sha256':receipt['sha256']}, indent=2))
        return receipt
