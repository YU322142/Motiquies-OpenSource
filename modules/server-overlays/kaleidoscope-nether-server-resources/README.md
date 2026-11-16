# kaleidoscope-nether-server-resources

Source-only offline historical producer. Read NOTICE.md and UPSTREAM.md for the original licensing boundaries and exact external input route. Only own functional transformation code, format/hash metadata and legal notices are supplied. No vendor binary, class, guidebook/resource JSON payload, art, world or QA actor is included.

```powershell
python build.py --input-jar D:/external/kaleidoscope_nether-1.1.2-neoforge+mc1.21.1.jar --output D:/external/build-kaleidoscope-nether-server-resources --java-home D:/external/temurin-21.0.10 --sdk D:/external/sdk
```

Yuushya uses the pinned external JDK jar archive tool; it compiles/executes no mod classes. The other two modules use no JDK or SDK, so these optional flags may be omitted. SDK/JDK paths, when supplied, remain protected from output even if unused. Build output must be a new directory outside the complete source repository, SDK and JDK. The builder detects publication-manifest.json or CANDIDATE-ROOT in its ancestors. It performs no download, game/server launch, SSH, deployment or Gradle action.

Exact input, own builder self-hash and toolchain pins are checked before output creation. Every source change requires updating producer.json sourceSha256 and rerunning a correct-input build; no verification flag bypass exists. The exact complete output hash and every changed/removed entry are checked; every class and all other entry payloads are preserved. Python executable/runtime DLL, zipfile module, version and zlib are pinned; Yuushya additionally pins the complete JDK tree. Standard-library files beyond those listed are not all independently tree-pinned; whole-output SHA256 fails closed on output differences.

Two fresh offline builds and production-reference byte comparisons are recorded separately. Runtime QA and whole-upstream source rebuilds are pending. Output receipt binaryPublicationCleared=false is intentional: legal source-tool publication and permission to redistribute an entire adapted mod archive require separate review. The supplied notices are also copied beside local build output without modifying the historical JAR.
