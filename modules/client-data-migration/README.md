# Client data migration and shared map downloads

First-party Motiquies client component, version 0.3.23, for Minecraft 1.21.1 / NeoForge 21.1.241. The component is published under MIT (`LICENSE`). Minecraft, Xaero, MapSyncer and Distant Horizons keep their own terms.

Runtime Java matches the Beta5 client source. It recognizes the Beta4 upgrade marker and detects unmerged routes for supported same-instance HMCL upgrades. It also handles Xaero interruption when closing a map session and applies a stencil workaround to Ponder and TaCZ on the affected Android library only. Repeated DH level-load notices still merge in chat and exceptions remain in the game log. The component handles shared routes, waypoint and JEI merges, negotiated map downloads and native map commits. Player data, accounts and caches belong in the operator's runtime directories.

```powershell
python build.py --sdk-root <external-sdk> --java-home <jdk21> --output <external-empty-output>
```

`build.ps1` provides equivalent `-SdkRoot`, `-JavaHome`, `-Output` options. `package_jar.py` delegates to the complete build. Output is `motiquies-client-migration-0.3.23.jar`, which contains the module classes, resources and MIT notice. The dependency lock includes Xaero's nested xaerolib, which the build extracts into the external output directory for compilation.

The build compiles the module and runs 24 test classes. The DH junction test creates a synthetic external link and verifies that migration preserves the linked target. A restricted Windows sandbox may require permission to create the test junction. All fixtures are synthetic. Two separate builds produced the same JAR as the issued Beta5 package, including all 95 compiled classes and resources.

Runtime context: `config/motiquies-client/previous-version.txt`, user-supplied `previous-instance.txt`, migration/DH completion markers and preferred-route choices drive migration. Shared paths remain `xaero/minimap/Multiplayer_Any Address`, native world-map directories and JEI `world/server/Motiquies`. An absolute previous-instance path is a runtime handoff, not a public sample copied from an operator. MapSyncer negotiated payloads require the matching server customizations; their availability is independent of compiling this client. No datapack is required to compile this module. The rest of the pack/launcher configuration is outside this source module's coverage.

## Offline SDK and deterministic build

Python 3.10+ standard library and an installed Java 21 JDK are required. Windows is tested; JDK executable selection also supports Linux names, but Linux has not been exercised. Builds download nothing and run no game. Supply legally obtained matching dependencies outside this source repository. `dependencies.lock.json` lists every used compile/runtime classpath archive in order, by relative path and SHA-256; unlisted SDK JARs are not selected. A nested dependency has both outer archive and member SHA-256 locked. Original archives remain read-only and all output, generated test files, extracted dependencies and bytecode must be outside repository and SDK.

SDK layout:

```text
<sdk-root>/game/mods/<locked mod archives>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

All outputs must use a new or empty directory. The receipt records source hashes, complete input classpath lock, Java/compiler versions, compiler executable hash and artifact SHA. Archives use sorted entries with fixed timestamps/modes and stored compression, making two fresh builds byte-identical under the tested exact toolchain. That does not assert every future JDK/Python version produces identical bytecode. Candidate ZIP-entry comparisons, rather than whole-ZIP hashes, distinguish code changes from deterministic packaging and added license notices. No dependency or generated `.class`/JAR belongs in the source repository.

The provenance records trace the preserved 0.3.15, 0.3.19, 0.3.20 and 0.3.23 sources to their reference builds. The lock requires the operator to provide the exact MapSyncer client snapshot shared by Beta3, Beta4 and Beta5.
