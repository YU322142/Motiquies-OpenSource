"""Compile the GD656 scoreboard count against the pinned NeoForge SDK and addon."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile, ZipInfo


SOURCE = Path(__file__).resolve().parent
REPOSITORY = SOURCE.parents[1]
NAME = "motiquies-gd656-online-count-0.2.0.jar"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(program, args, output, stage):
    response = output / f"{stage}.args"
    response.write_text("\n".join('"' + str(arg).replace("\\", "/") + '"' for arg in args), encoding="utf-8")
    log = subprocess.run([str(program), "@" + str(response)], capture_output=True, timeout=60)
    (output / f"{stage}.log").write_bytes(log.stdout + log.stderr)
    if log.returncode:
        raise RuntimeError(f"{stage} failed. See {output / (stage + '.log')}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gd656", type=Path, required=True, help="Official GD656Killicon NeoForge 1.21.1 JAR")
    args = parser.parse_args()
    output = args.output.resolve()
    sdk = args.sdk_root.resolve(strict=True)
    if output.is_relative_to(REPOSITORY) or output.is_relative_to(sdk):
        raise ValueError("Build output must be outside the source repository and SDK")
    lock = json.loads((SOURCE / "dependencies.lock.json").read_text(encoding="utf-8"))
    jars = [sdk / entry["path"] for entry in lock["classpath"]]
    for path, entry in zip(jars, lock["classpath"], strict=True):
        if digest(path.read_bytes()) != entry["sha256"]:
            raise ValueError(f"Different SDK input: {entry['path']}")
    gd656 = args.gd656.resolve(strict=True)
    if digest(gd656.read_bytes()) != lock["gd656"]["sha256"]:
        raise ValueError("GD656Killicon does not match the locked official input")
    extras_entry = lock["mixinExtras"]
    with ZipFile(next(path for path in jars if path.name == "neoforge-21.1.241-universal.jar")) as package:
        extras_bytes = package.read(extras_entry["path"])
    if digest(extras_bytes) != extras_entry["sha256"]:
        raise ValueError("Nested MixinExtras archive does not match the lock")
    jdk = args.java_home.resolve() / "bin"
    suffix = ".exe" if os.name == "nt" else ""
    javac = jdk / f"javac{suffix}"
    result = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=True, timeout=20)
    javac_version = (result.stdout + result.stderr).strip()
    if javac_version != lock["javacVersion"]:
        raise ValueError(f"Expected {lock['javacVersion']}, got {javac_version}")
    output.mkdir(parents=True)
    extras = output / Path(extras_entry["path"]).name
    extras.write_bytes(extras_bytes)
    classpath = os.pathsep.join(map(str, [*jars, extras, gd656]))
    classes, tests = output / "classes", output / "tests"
    production_args = ["--release", 21, "-proc:none", "-encoding", "UTF-8"]
    run(jdk / f"javac{suffix}", [*production_args, "-cp", classpath, "-d", classes,
                               *sorted((SOURCE / "src/main/java").rglob("*.java"))], output, "compile")
    classpath = os.pathsep.join([str(classes), classpath])
    run(jdk / f"javac{suffix}", [*production_args, "-cp", classpath, "-d", tests,
                               *sorted((SOURCE / "src/test/java").rglob("*.java"))], output, "compile-tests")
    run(jdk / f"java{suffix}", ["-cp", os.pathsep.join([str(tests), classpath]),
                              "org.motiquies.client.tab.OnlineCountTextTest"], output, "test")
    artifact = output / NAME
    with ZipFile(artifact, "x") as package:
        package.writestr(ZipInfo("LICENSE"), (SOURCE / "LICENSE").read_bytes())
        for root in (classes, SOURCE / "src/main/resources"):
            for path in sorted(root.rglob("*")):
                if path.is_file():
                    package.writestr(ZipInfo(path.relative_to(root).as_posix()), path.read_bytes())
    receipt = {"artifact": NAME, "sha256": digest(artifact.read_bytes()), "sdk": lock["classpath"],
               "compiler": javac_version, "gd656Sha256": lock["gd656"]["sha256"],
               "mixinExtrasSha256": digest(extras.read_bytes()), "componentTestPassed": True}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
