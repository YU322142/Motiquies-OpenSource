# Client data migration and shared map downloads

First-party Motiquies client component, version 0.3.20, for Minecraft 1.21.1 / NeoForge 21.1.241. The 70 source, resource and test files are published under MIT (`LICENSE`). Minecraft, Xaero, MapSyncer and Distant Horizons keep their own terms.

Runtime Java matches the Beta4 client source. Beta4 recognizes a previous Beta3 instance when an upgrade supplies its version and merges repeated DH level-load chat notices without changing how DH reports exceptions in the log. The published package uses MIT license metadata and LF source text. The component also handles shared routes, waypoint and JEI merges, negotiated map downloads and native map commits. Player data, accounts and caches belong in the operator's runtime directories.

```powershell
python build.py --sdk-root <external-sdk> --java-home <jdk21> --output <external-empty-output>
```

`build.ps1` provides equivalent `-SdkRoot`, `-JavaHome`, `-Output` options. `package_jar.py` delegates to the complete build. Output is `motiquies-client-migration-0.3.20.jar`, which contains the module classes, resources and MIT notice. The dependency lock includes Xaero's nested xaerolib, which the build extracts into the external output directory for compilation.

The build compiles the module and runs 23 test classes. The DH junction test creates a synthetic external link and verifies that migration preserves the linked target. A restricted Windows sandbox may require permission to create the test junction. All fixtures are synthetic.

Runtime context: `config/motiquies-client/previous-version.txt`, user-supplied `previous-instance.txt`, migration/DH completion markers and preferred-route choices drive migration. Shared paths remain `xaero/minimap/Multiplayer_Any Address`, native world-map directories and JEI `world/server/Motiquies`. An absolute previous-instance path is a runtime handoff, not a public sample copied from an operator. MapSyncer negotiated payloads require the matching server customizations; their availability is independent of compiling this client. No datapack is required to compile this module. The rest of the pack/launcher configuration is outside this source module's coverage.

## Offline SDK and deterministic build

Python 3.10+ standard library and an installed Java 21 JDK are required. Windows is tested; JDK executable selection also supports Linux names, but Linux has not been exercised. Builds download nothing and run no game. Supply legally obtained matching dependencies outside this source repository. `dependencies.lock.json` lists every used compile/runtime classpath archive in order, by relative path and SHA-256; unlisted SDK JARs are not selected. A nested dependency has both outer archive and member SHA-256 locked. Original archives remain read-only and all output, generated test files, extracted dependencies and bytecode must be outside repository and SDK.

SDK layout:

```text
<sdk-root>/game/mods/<locked mod archives>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

All outputs must use a new or empty directory. The receipt records source hashes, complete input classpath lock, Java/compiler versions, compiler executable hash and artifact SHA. Archives use sorted entries with fixed timestamps/modes and stored compression, making two fresh builds byte-identical under the tested exact toolchain. That does not assert every future JDK/Python version produces identical bytecode. Candidate ZIP-entry comparisons, rather than whole-ZIP hashes, distinguish code changes from deterministic packaging and added license notices. No dependency or generated `.class`/JAR belongs in the source repository.

`SOURCE-PROVENANCE.json`, `BETA3-PROVENANCE.json` and `BETA4-PROVENANCE.json` record the 0.3.15 baseline, the 0.3.19 source delta and the 0.3.20 source delta with its release fingerprints. The lock requires the operator to provide the exact MapSyncer client snapshot shared by Beta3 and Beta4.
