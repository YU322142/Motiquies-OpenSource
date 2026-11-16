"""Offline QA-only compiler/packager. Never downloads dependencies or starts Minecraft."""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent

def digest(data):
    return hashlib.sha256(data).hexdigest()

def within(child, parent):
    return child == parent or parent in child.parents

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-root', type=Path, required=True,
                        help='Read-only installed NeoForge server SDK matching dependencies.lock.json')
    parser.add_argument('--java-home', type=Path, required=True, help='Installed Java 21 JDK')
    parser.add_argument('--output', type=Path, required=True, help='New/empty external build directory')
    args = parser.parse_args()
    sdk, output = args.sdk_root.resolve(strict=True), args.output.resolve()
    if within(output, ROOT.parents[2]) or within(output, sdk):
        raise ValueError('Output must be outside the source repository and SDK')
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError('Output directory must be new or empty')
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text(encoding='utf-8'))
    paths = []
    for entry in lock['classpath']:
        path = (sdk / entry['path']).resolve(strict=True)
        if not within(path, sdk):
            raise ValueError('Dependency escapes SDK: ' + entry['path'])
        if digest(path.read_bytes()) != entry['sha256']:
            raise ValueError('Dependency SHA-256 mismatch: ' + entry['path'])
        paths.append(path)
    for name, expected in lock['originalBuildInputs'].items():
        if digest((ROOT / name).read_bytes()) != expected:
            raise ValueError('Original source/build input SHA-256 mismatch: ' + name)
    java_home = args.java_home.resolve(strict=True)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = java_home / 'bin' / ('javac' + suffix)
    output.mkdir(parents=True, exist_ok=True)
    commands = []
    def run(command, logname):
        result = subprocess.run(list(map(str, command)), cwd=output, capture_output=True,
                                text=True, encoding='utf-8', errors='replace')
        log = result.stdout + result.stderr
        (output / logname).write_text(log, encoding='utf-8')
        commands.append({'argv':list(map(str,command)), 'exitCode':result.returncode, 'output':log})
        if result.returncode:
            raise RuntimeError(logname + ' failed\n' + log)
        return log.strip()
    version = run([javac, '-version'], 'javac-version.log')
    if not version.startswith('javac 21.'):
        raise ValueError('A Java 21 compiler is required')
    classes = output / 'classes'
    classes.mkdir()
    # Annotation processors from external classpath JARs are never executed.
    options = ['--release','21','-proc:none','-g','-encoding','UTF-8','-cp',
               os.pathsep.join(map(str,paths)), '-d',str(classes)]
    options += [str(p) for p in sorted((ROOT / 'src/main/java').rglob('*.java'))]
    argfile = output / 'javac.args'
    argfile.write_text('\n'.join('"' + value.replace('\\','/') + '"' for value in options), encoding='utf-8')
    run([javac, '@' + str(argfile)], 'javac.log')
    properties = {}
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.lstrip().startswith(('#','!')):
            key, value = line.split('=',1)
            properties[key.strip()] = value.strip()
    entries = {'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\r\n\r\n',
               'LICENSE':(ROOT / 'LICENSE').read_bytes()}
    for path in sorted(classes.rglob('*.class')):
        name = path.relative_to(classes).as_posix()
        if not name.startswith('com/migration/'):
            raise ValueError('Unexpected external class: ' + name)
        entries[name] = path.read_bytes()
    for path in sorted((ROOT / 'src/main/resources').rglob('*')):
        if path.is_file():
            name = path.relative_to(ROOT / 'src/main/resources').as_posix()
            content = path.read_bytes()
            if name == 'META-INF/neoforge.mods.toml':
                content = re.sub(r'\$\{([a-z_]+)\}', lambda m: properties[m[1]],
                                 content.decode('utf-8')).encode('utf-8')
            if name in entries:
                raise ValueError('Duplicate JAR entry: ' + name)
            entries[name] = content
    artifact = output / (lock['artifactName'])
    with zipfile.ZipFile(artifact, 'w', compression=zipfile.ZIP_STORED) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026,11,16,0,0,0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)
    with zipfile.ZipFile(artifact) as z:
        if z.testzip() is not None:
            raise ValueError('JAR CRC verification failed')
    for entry, path in zip(lock['classpath'], paths):
        if digest(path.read_bytes()) != entry['sha256']:
            raise ValueError('SDK input changed during build: ' + entry['path'])
    receipt = {'artifact':artifact.name, 'sha256':digest(artifact.read_bytes()),
               'entrySha256':{name:digest(data) for name,data in sorted(entries.items())},
               'dependencyLockSha256':digest((ROOT/'dependencies.lock.json').read_bytes()),
               'classpath':lock['classpath'],'javacVersion':version,
               'javacExecutableSha256':digest(javac.read_bytes()),'commands':commands,
               'sdkInputsPreserved':True,'qaOnly':True,'productionApproved':False,'gameExecuted':False}
    (output / 'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'artifact':str(artifact),'sha256':receipt['sha256']}))

if __name__ == '__main__':
    main()
