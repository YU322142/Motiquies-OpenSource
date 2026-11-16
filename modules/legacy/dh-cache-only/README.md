# Historical DH cache-only overlay producer

Reproduces a historical production overlay on Distant Horizons3.2.0-b for Minecraft1.21.1 (Fabric/NeoForge). This module is historical maintenance, separate from the3.3.0 target of official native DH. It neither changes that target nor installs or enables anything.

Own producer/tooling: GPL-3.0-only under owner authorization, not MIT. Distant Horizons stays under its original LGPL3 terms, with exact GPL3/LGPL3 texts, original author metadata, bundled notices and Pankakes CC BY-SA4.0 logo notice preserved. No upstream JAR, class, logo, runtime state or diagnostic agent is in this source module.

Use Python3.11+ and JDK21.0.10 (tested Temurin21.0.10+7). Obtain the original [official Modrinth3.2.0-b1.21.1 release](https://modrinth.com/mod/distanthorizons/version/ZpKb4kZp) or [CurseForge file8389148](https://www.curseforge.com/minecraft/mc-mods/distant-horizons/files/8389148), plus ASM9.8 asm and asm-tree JARs. Full exact input/tool SHA256 values are in producer.json and dependencies.lock.json. SDK subdirectory layout is flexible; dependencies are searched by pinned filename and hash. Nothing is downloaded automatically.

```text
python -B build.py --input-jar <original-DH.jar> --java-home <jdk21-directory> --dependencies <external-ASM-directory> --output <new-external-output-directory>
```

`--sdk` is an alias for `--dependencies`. The builder rejects changed/already-patched input and dependency drift before creating output, compiles only PatchCacheOnly.java, and runs only that offline bytecode producer. It verifies exactly two changed class entries and all other original entry contents/order/notices unchanged. It writes the modified JAR, receipt and accompanying modification/source/license notices in the external output directory. Do not separate the JAR from its accompanying notices.

The own producer prefixes two methods with Boolean.getBoolean("motiquies.dh.cacheOnly"). When enabled, the existing generation eligibility lookup returns true and world-generation requests route into the existing LOD synchronization handler. Without the flag, execution falls through to original instructions. The property state on the observed production deployment is UNKNOWN; this build does not inspect or change it.

Two deterministic producer builds and exact changed-class comparison are the verification scope. No DH/game process, network conversion, diagnostic agent, migration or runtime acceptance test runs. Read CORRESPONDING-SOURCE.md for the official wrapper/core revision route and unresolved complete-source verification. No modified upstream binary is published here; binary/source/license compliance review remains separate.
