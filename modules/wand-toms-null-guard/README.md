# Construction Wand / Tom storage nullable extraction guard

Own ASM producer and original red/green test harness are included under MIT (`LICENSE`). Construction Wand KOTS remains ThetaDev/Polaris_Light's work; the original MIT notice `Copyright (c) 2025 Polaris_Light` is preserved in `LICENSE-UPSTREAM-ConstructionWand.txt` and in rebuilt output. See `NOTICE.md`. The complete reference handler source is unnecessary for this producer and is excluded; no original Minecraft/Tom storage implementation is bundled.

Supply the legally obtained original `constructionwand-kots-1.21.1-2.16.9.jar` separately. Required SHA-256 is `cf6ed4cf33d7cd44e026c4e6d2d337d022db7050a861ee11cae92c6bb3507b8f`. The SDK supplies the exact locked ASM 9.8/asm-tree/asm-util JARs, without game libraries or data.

```powershell
python build.py --sdk-root <external-sdk> --java-home <jdk21> --output <external-empty-output> --base <original-constructionwand.jar>
```

`build_and_test.py` is the equivalent original named entrypoint. It preserves the original `WandTomsPatch.java` producer byte-for-byte. The actual supplied original useItems bytecode reproduces the null-pull crash in RED, then the inserted nullable result guard passes the original missing/client/null/unavailable/partial/full/repeated/zero boundary checks with handwritten terminal/world adapters. All six unrelated method traces and every original other archive entry must remain unchanged. The handler is upstream-derived modified code, even though the producer is independently authored; this is not a blanket own MIT claim over vendor bytes.

The output `constructionwand-kots-1.21.1-2.16.9-toms-null-guard.1.jar` is reconstructed locally from the user's original archive. Three additional notice entries retain original/own licensing. Extracted and harness bytecode stays in external test output. Actual item/world integration and production acceptance are separate from these offline contracts. No config, datapack, real world, player file or network service is needed to reproduce this patch.

## Offline SDK and deterministic build

Python 3.10+ standard library and an installed Java 21 JDK are required. Windows is tested; JDK executable selection also supports Linux names, but Linux has not been exercised. Builds download nothing and run no game. Supply legally obtained matching dependencies outside this source repository. `dependencies.lock.json` lists every used compile/runtime classpath archive in order, by relative path and SHA-256; unlisted SDK JARs are not selected. A nested dependency has both outer archive and member SHA-256 locked. Original archives remain read-only and all output, generated test files, extracted dependencies and bytecode must be outside repository and SDK.

SDK layout:

```text
<sdk-root>/game/mods/<locked mod archives>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

All outputs must use a new or empty directory. The receipt records source hashes, complete input classpath lock, Java/compiler versions, compiler executable hash and artifact SHA. Archives use sorted entries with fixed timestamps/modes and stored compression, making two fresh builds byte-identical under the tested exact toolchain. That does not assert every future JDK/Python version produces identical bytecode. Candidate ZIP-entry comparisons, rather than whole-ZIP hashes, distinguish code changes from deterministic packaging and added license notices. No dependency or generated `.class`/JAR belongs in the source repository.

`SOURCE-PROVENANCE.json` records original canonical file hashes, public hashes and each adaptation. Canonical source is `b94bdffa1b2df170ea49d0271b992b56c9a36fef`. Source publication is separate from actual game acceptance, production deployment and general release approval.
