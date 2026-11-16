# GPL MapSyncer hybrid saved-region and download overlay

This is a complete reproducible source-to-source overlay route, not a standalone replacement for the full upstream source. MapSyncer is by Ruo_Chen/RuoChennn and declares GPL-3.0. This module and its generated modified upstream classes are GPL version 3; see `LICENSE-GPL-3.0.txt` and `NOTICE.md`. The original embedded QOS.ch/Sarl notice is MIT for its third-party component, is separately retained and is not mistaken for the MapSyncer GPL text. No blanket MIT grant is applied to upstream classes.

Supply original upstream source externally from [1.0.4beta2](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap/releases/tag/1.0.4beta2), exact commit `04e5d9c542d5fd0e56fb63b3be87f0dba3091a47`. Original source archive SHA-256 is `e48ccc986efd98139aeec234d310578c6c67cc379ba9209efed43c3dee60ece1`; all 12 source files actually read by the producer are individually pinned in `upstream-source.lock.json` and must match before generation and after tests. The complete original upstream tree remains obtainable at that pinned source version; no unlicensed vendor tree is copied into this repository. When distributing a rebuilt GPL binary, preserve the complete corresponding upstream source and these modifications/build scripts under the applicable GPL terms; this source-only project is not a statement that merely relabeling a binary meets those terms.

The original official beta2 NeoForge JAR must be separately supplied with SHA-256 `7e96f19dc703fe1a8c22b7deea8144e2b69c3632b3afc591d2bb84024f8c184a`.

```powershell
python build.py --sdk-root <external-sdk> --java-home <jdk21> --output <external-empty-output> --base <original-beta2.jar> --upstream-root <external-pinned-source-tree>
```

`build_mapsync.py`, `run_tests.py`, `run_snapshot_tests.py` and `run_freshness_tests.py` are equivalent complete public-build entrypoints with those same arguments. The preserved audited transformations in `build_mapsync.py` and `policy_patch.py` generate full modified upstream sources only in external output, compile those plus every current patchsrc, and overlay the original archive. Original client archive entries stay unchanged. This route reconstructs all modified classes; copying only patchsrc would not. JAR output is `mapsyncer-motiquies-hybrid-source-neoforge-1.21.1.jar`. It retains original archive notices and adds real GPL text and explicit modifications/attribution.

All 17 original Java regression classes execute against the freshly compiled overlay. They include download policy/snapshot/heartbeat/rate/estimates, dirty queue and metadata, real native MCA/NBT snapshot IO, source-freshness fences, writer determinism, empty layer publication, saved-level lifecycle and synthetic 65,537/100,000-region replay with restart. `verify_artifact.py` retains its original optional progress channel-guard and permitted-entry/client-preservation checks, with explicit external base/Java/output arguments. Native IO here means isolated test fixtures, not a running Minecraft server or a copy of a player's world.

Runtime deployment requires separate configuration/data: `motiquies.mapsync.active` may identify a publisher manifest with `artifacts.xaero`; `motiquies.mapsync.snapshot` is an explicit frozen converter input root. Dirty journals, snapshot inputs, map ZIPs, real MCA/worlds and player download policy are runtime state, excluded from source. No datapack is a compile input. Publisher/launcher/deployment integration is outside this module; compilation alone does not recreate a production world or its map.

Version boundary: production inventory identifies the snapshot archive SHA `eb8f12b8b083a13a21bd71145c8ab4c5f2be6046fe2c942d146292ceaa61afe3`; a later server QA stage6i candidate has SHA `4546ac072a6c212d727c417044595d0c65194c8ec615326782f9be4183e6bdd5`. Neither label is asserted to equal this public build without the recorded per-entry comparison. Source coverage and future-candidate validation must not be misreported as production deployment.

## Offline SDK and deterministic build

Python 3.10+ standard library and an installed Java 21 JDK are required. Windows is tested; JDK executable selection also supports Linux names, but Linux has not been exercised. Builds download nothing and run no game. Supply legally obtained matching dependencies outside this source repository. `dependencies.lock.json` lists every used compile/runtime classpath archive in order, by relative path and SHA-256; unlisted SDK JARs are not selected. A nested dependency has both outer archive and member SHA-256 locked. Original archives remain read-only and all output, generated test files, extracted dependencies and bytecode must be outside repository and SDK.

SDK layout:

```text
<sdk-root>/game/mods/<locked mod archives>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

All outputs must use a new or empty directory. The receipt records source hashes, complete input classpath lock, Java/compiler versions, compiler executable hash and artifact SHA. Archives use sorted entries with fixed timestamps/modes and stored compression, making two fresh builds byte-identical under the tested exact toolchain. That does not assert every future JDK/Python version produces identical bytecode. Candidate ZIP-entry comparisons, rather than whole-ZIP hashes, distinguish code changes from deterministic packaging and added license notices. No dependency or generated `.class`/JAR belongs in the source repository.

`SOURCE-PROVENANCE.json` records original canonical file hashes, public hashes and each adaptation. Canonical source is `b94bdffa1b2df170ea49d0271b992b56c9a36fef`. Source publication is separate from actual game acceptance, production deployment and general release approval.
