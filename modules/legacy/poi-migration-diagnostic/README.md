# POI-Migration-Diagnostic — legacy QA source

**QA-only; non-production; releaseEligible=false.** Inspects POI/villager state. This is **not read-only**: it can write diagnostic NBT, load chunks, create/load entities and spawn raw entities. Never run these commands on production worlds.

Original source: [YU322142/POI-Migration-Diagnostic](https://github.com/YU322142/POI-Migration-Diagnostic) at `66403b50508284692d054093a53a744bf51e2bfd`. Original source bytes and “Migration audit” author credit are preserved; see `PROVENANCE.json` and `UPSTREAM_README.md`. This module adds an owner-authorized complete MIT LICENSE and a direct compiler build, without changing the Java implementation.

## Verified offline build

Requirements: Python 3.10+ and a Java 21 JDK (verification uses Temurin 21.0.10+7). Supply a legally acquired, installed NeoForge **21.1.241 / Minecraft 1.21.1** server SDK containing the exact `libraries/` paths and SHA-256 values in `dependencies.lock.json`. Install NeoForge through its official installer outside this repository; obtain each external component under its own terms. Generated NeoForge server JAR bytes must match the lock. The script refuses every mismatched dependency. No SDK or game binaries are included or downloaded.

From this module directory, run the following, replacing the paths with your existing SDK/JDK and a **new external** output directory:

```powershell
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/poi-migration-diagnostic/run-a'
python -B build.py --sdk-root 'D:/SDK/neoforge-21.1.241' --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot' --output 'D:/Builds/poi-migration-diagnostic/run-b'
```

This invokes `javac --release 21 -proc:none -g`, expands the original TOML properties, and packages only this module's own classes/resources plus MIT LICENSE. It never starts Java mod code, Minecraft or Gradle. `receipt.json` records input hashes, compiler identity, every entry hash and artifact SHA-256. Compare the two receipt artifact SHA values to verify repeatability with the same toolchain. Outputs inside the repository or SDK and nonempty output directories are rejected.

The old Gradle files are retained for provenance. The upstream `gradlew.bat` commands cannot work in this seven-file snapshot because no Gradle wrapper was committed. Use the verified direct compiler command above.

## Licensing and limits

MIT applies to the owner's original source in this module. Original README declares MIT; author metadata remains “Migration audit”. External Minecraft, NeoForge, Brigadier, SLF4J and other SDK libraries retain their own licenses and notices; this module does not relicense or redistribute them. Importing classes is not a claim to own their implementations. Full backups preserve exact original commits and release bytes.

Reproducible compilation is separate from byte identity with the historical release and from game validation. No in-game verification, production authorization or JAR publication is implied.

## Build evidence (2026-11-16)

Two independent external builds with `javac 21.0.10` produced identical whole-JAR and per-entry SHA-256 values. Build boundary tests rejected output inside the source repository or SDK, and a mismatched dependency was rejected before compilation. All original source files remain byte-exact; SDK input hashes passed before and after compilation. Details: `BUILD-VERIFICATION.json`.

Rebuilt QA artifact SHA-256: `1708e7f097d48d8a5a404f8956d6d320734ebd4d889c1dea8e3a39ebc9262956`.

**Unresolved historical reproduction blocker:** the historical release's class bytes differ from this public-source rebuild. The published source adds `loadWatchedVillagers()` and configurable, sanitized watchlist defaults; the old binary does not have that method and contains different identifiers. The precise old customization and default watch behavior are **not reproduced or claimed complete**. Original release bytes are privately preserved and its existing public URL is unchanged; original watch identifiers are not exported, guessed or copied here.

`watchlist.example.properties` documents the required shape with placeholders only. Replace its four placeholders with your own villager entity UUIDs. The original module reads the JVM system property `migration.poi.watchedVillagers` and requires exactly four UUIDs; it does **not** automatically read this example file. Configure that property only in your existing isolated QA launch configuration. The template does not launch anything or change the original public Java source.
