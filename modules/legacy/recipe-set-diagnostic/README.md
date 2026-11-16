# Recipe-Set-Diagnostic — legacy QA source

**QA-only; non-production; releaseEligible=false.** Logs recipe identifiers, namespace counts and a deterministic hash for controlled QA comparisons. It is not a production customization.

Original source: [YU322142/Recipe-Set-Diagnostic](https://github.com/YU322142/Recipe-Set-Diagnostic) at `546ad65b35c6f24618ab4aef6972250aa0b6e984`. Original source bytes and “Migration audit” author credit are preserved; see `PROVENANCE.json` and `UPSTREAM_README.md`. This module adds an owner-authorized complete MIT LICENSE and a direct compiler build, without changing the Java implementation.

## Verified offline build

Requirements: Python 3.10+ and a Java 21 JDK (verification uses Temurin 21.0.10+7). Supply a legally acquired, installed NeoForge **21.1.241 / Minecraft 1.21.1** server SDK containing the exact `libraries/` paths and SHA-256 values in `dependencies.lock.json`. Install NeoForge through its official installer outside this repository; obtain each external component under its own terms. Generated NeoForge server JAR bytes must match the lock. The script refuses every mismatched dependency. No SDK or game binaries are included or downloaded.

From this module directory, run the following, replacing the paths with your existing SDK/JDK and a **new external** output directory:

```powershell
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/recipe-set-diagnostic/run-a'
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/recipe-set-diagnostic/run-b'
```

This invokes `javac --release 21 -proc:none -g`, expands the original TOML properties, and packages only this module's own classes/resources plus MIT LICENSE. It never starts Java mod code, Minecraft or Gradle. `receipt.json` records input hashes, compiler identity, every entry hash and artifact SHA-256. Compare the two receipt artifact SHA values to verify repeatability with the same toolchain. Outputs inside the repository or SDK and nonempty output directories are rejected.

The old Gradle files are retained for provenance. The upstream `gradlew.bat` commands cannot work in this seven-file snapshot because no Gradle wrapper was committed. Use the verified direct compiler command above.

## Licensing and limits

MIT applies to the owner's original source in this module. Original README declares MIT; author metadata remains “Migration audit”. External Minecraft, NeoForge, Brigadier, SLF4J and other SDK libraries retain their own licenses and notices; this module does not relicense or redistribute them. Importing classes is not a claim to own their implementations. Full backups preserve exact original commits and release bytes.

Reproducible compilation is separate from byte identity with the historical release and from game validation. No in-game verification, production authorization or JAR publication is implied.

## Build evidence (2026-11-16)

Two independent external builds with `javac 21.0.10` produced identical whole-JAR and per-entry SHA-256 values. Build boundary tests rejected output inside the source repository or SDK, and a mismatched dependency was rejected before compilation. All original source files remain byte-exact; SDK input hashes passed before and after compilation. Details: `BUILD-VERIFICATION.json`.

Rebuilt QA artifact SHA-256: `dca3595122ea8f3e0d24114655d84be194fc5c10e124584c1d033717ae4acfaf`.

Every original non-directory class, manifest and mod-resource entry matches the preserved historical release byte for byte. The rebuilt QA JAR adds only `LICENSE`; whole-JAR bytes differ because packaging metadata/order/compression and license inclusion differ. No runtime equivalence or game test was performed.
