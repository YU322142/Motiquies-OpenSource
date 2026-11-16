# Client data migration and shared map downloads

Complete current first-party Motiquies client component, version 0.3.15, for Minecraft 1.21.1 / NeoForge 21.1.241. The current 63 source/resource/test files are included; obsolete duplicated history snapshots and private QA receipts are excluded. Owner-authorized own source is MIT (`LICENSE`); the prior own ARR metadata is updated. Minecraft, Xaero and MapSyncer remain third-party external dependencies under their original terms. No third-party implementation or personal user files are bundled.

Runtime Java source bytes are preserved. The component retains prior-version/instance migration, server-route sharing, waypoint/JEI merge protection, DH legacy-cache rules, negotiated map-download UI/control, native map commit/reload handling and shader resize scheduling. Default official service endpoints are visible source constants, not credentials. User accounts, worlds, saved servers, waypoints, JEI history/favourites and caches are runtime data and must stay private.

```powershell
python build.py --sdk-root <external-sdk> --java-home <jdk21> --output <external-empty-output>
```

`build.ps1` provides equivalent `-SdkRoot`, `-JavaHome`, `-Output` options. `package_jar.py` delegates to the complete tested build rather than silently packaging stale classes. Output is `motiquies-client-migration-0.3.15.jar` containing only own classes/resources/license. The lock includes Xaero's nested xaerolib; it is extracted solely into external build output for compilation and is never packaged in this module.

The build compiles all current source and runs all 21 original test classes. Twenty ordinary file/policy tests run in isolated external temporary storage; the original DH junction-escape test additionally creates one synthetic external junction/symlink and confirms its victim survives and no completion marker is written. A restricted Windows sandbox may require permission to create that test junction; a failed creation is a failed build, not silently skipped acceptance. No fixture is sourced from a player's files.

Runtime context: `config/motiquies-client/previous-version.txt`, user-supplied `previous-instance.txt`, migration/DH completion markers and preferred-route choices drive migration. Shared paths remain `xaero/minimap/Multiplayer_Any Address`, native world-map directories and JEI `world/server/Motiquies`. An absolute previous-instance path is a runtime handoff, not a public sample copied from an operator. MapSyncer negotiated payloads require the matching server customizations; their availability is independent of compiling this client. No datapack is required to compile this module. The rest of the pack/launcher configuration is outside this source module's coverage.

## Offline SDK and deterministic build

Python 3.10+ standard library and an installed Java 21 JDK are required. Windows is tested; JDK executable selection also supports Linux names, but Linux has not been exercised. Builds download nothing and run no game. Supply legally obtained matching dependencies outside this source repository. `dependencies.lock.json` lists every used compile/runtime classpath archive in order, by relative path and SHA-256; unlisted SDK JARs are not selected. A nested dependency has both outer archive and member SHA-256 locked. Original archives remain read-only and all output, generated test files, extracted dependencies and bytecode must be outside repository and SDK.

SDK layout:

```text
<sdk-root>/game/mods/<locked mod archives>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

All outputs must use a new or empty directory. The receipt records source hashes, complete input classpath lock, Java/compiler versions, compiler executable hash and artifact SHA. Archives use sorted entries with fixed timestamps/modes and stored compression, making two fresh builds byte-identical under the tested exact toolchain. That does not assert every future JDK/Python version produces identical bytecode. Candidate ZIP-entry comparisons, rather than whole-ZIP hashes, distinguish code changes from deterministic packaging and added license notices. No dependency or generated `.class`/JAR belongs in the source repository.

`SOURCE-PROVENANCE.json` records original canonical file hashes, public hashes and each adaptation. Canonical source is `b94bdffa1b2df170ea49d0271b992b56c9a36fef`. Source publication is separate from actual game acceptance, production deployment and general release approval.
