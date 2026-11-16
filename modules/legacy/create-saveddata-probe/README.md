# Create-SavedData-Probe — legacy QA source

**QA-only; non-production; releaseEligible=false.** Inspects and logs Create railway/logistics SavedData through API calls and reflection during isolated QA. Never install it in production.

Original source: [YU322142/Create-SavedData-Probe](https://github.com/YU322142/Create-SavedData-Probe) at `ab54f95987bd67b383f493f0011229514ab4a1db`. Original source bytes and “Migration audit” author credit are preserved; see `PROVENANCE.json` and `UPSTREAM_README.md`. This module adds an owner-authorized complete MIT LICENSE and a direct compiler build, without changing the Java implementation.

## Verified offline build

Requirements: Python 3.10+ and a Java 21 JDK (verification uses Temurin 21.0.10+7). Supply a legally acquired, installed NeoForge **21.1.241 / Minecraft 1.21.1** server SDK containing the exact `libraries/` paths and SHA-256 values in `dependencies.lock.json`. Install NeoForge through its official installer outside this repository; obtain each external component under its own terms. Generated NeoForge server JAR bytes must match the lock. The script refuses every mismatched dependency. No SDK or game binaries are included or downloaded.

From this module directory, run the following, replacing the paths with your existing SDK/JDK and a **new external** output directory:

```powershell
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/create-saveddata-probe/run-a'
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/create-saveddata-probe/run-b'
```

This invokes `javac --release 21 -proc:none -g`, expands the original TOML properties, and packages only this module's own classes/resources plus MIT LICENSE. It never starts Java mod code, Minecraft or Gradle. `receipt.json` records input hashes, compiler identity, every entry hash and artifact SHA-256. Compare the two receipt artifact SHA values to verify repeatability with the same toolchain. Outputs inside the repository or SDK and nonempty output directories are rejected.

The old Gradle files are retained for provenance. The upstream `gradlew.bat` commands cannot work in this seven-file snapshot because no Gradle wrapper was committed. Use the verified direct compiler command above.

## Licensing and limits

MIT applies to the owner's original source in this module. Original README declares MIT; author metadata remains “Migration audit”. External Minecraft, NeoForge, Brigadier, SLF4J and other SDK libraries retain their own licenses and notices; this module does not relicense or redistribute them. Importing classes is not a claim to own their implementations. Full backups preserve exact original commits and release bytes.

Reproducible compilation is separate from byte identity with the historical release and from game validation. No in-game verification, production authorization or JAR publication is implied.

Create input: place your legally acquired `create-1.21.1-6.0.10.jar` at `mods/create-1.21.1-6.0.10.jar` in the external SDK. This direct build uses the lock, not the obsolete `<TRANS_ROOT>` path in original Gradle properties. Current exact input SHA-256: `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`. No Create code is bundled into the output.

## Build evidence (2026-11-16)

Two independent external builds with `javac 21.0.10` produced identical whole-JAR and per-entry SHA-256 values. Build boundary tests rejected output inside the source repository or SDK, and a mismatched dependency was rejected before compilation. All original source files remain byte-exact; SDK input hashes passed before and after compilation. Details: `BUILD-VERIFICATION.json`.

Rebuilt QA artifact SHA-256: `60f6b8095a8465db646f4cdb61fbeecba6d23c1ce60886904ed9b7e1030ac30e`.

Every original non-directory class, manifest and mod-resource entry matches the preserved historical release byte for byte. The rebuilt QA JAR adds only `LICENSE`; whole-JAR bytes differ because packaging metadata/order/compression and license inclusion differ. No runtime equivalence or game test was performed.
