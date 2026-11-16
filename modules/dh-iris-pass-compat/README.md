# DH / Iris normal-pass compatibility

First-party standalone client compatibility source for Minecraft 1.21.1, NeoForge 21.1.241, Distant Horizons 3.2.0-b and the pinned Iris 1.8.13 snapshot/local build. It synchronizes the current Iris override decision immediately before DH selects its normal render pass; deferred calls retain the normal call's state. Runtime Java sources are preserved from the audited canonical source. The packaged license entry has the explicit first-party tooling scope clarification.

Builds compile the actual production class against supplied original mod libraries. The original tests preserve their assertions and handwritten boundary fixtures: they extract DH selection instructions from the user's supplied JAR locally, reproduce the original RED stale-pass failure, check GREEN first enable/deferred/pipeline changes/shader disable/absent/null transitions, then exercise the actual production-compiled handler. They do not establish exact Mixin weaving, real world switching/joining or GPU behavior/performance. No game runs during these commands. The DH integration calls Iris internal APIs and is tied to these exact input hashes; API compatibility with other versions is not claimed.

`build.py` is the standard entry; `build_b10.py` is the equivalent retained original named entry. Both run the original boundary tests and all eight generic adapter tests before packaging. Direct boundary testing accepts `test_b10.py red|green` plus the same three external-path options. The expected RED failure is checked explicitly; an arbitrary failure does not count as success. All copied/extracted upstream classes remain exclusively in external local test output. No third-party class is packaged.

`local_adapter.py` is a separately usable generic offline ZIP engine:

```powershell
python local_adapter.py <user-input.zip> <separately-reviewed-recipe.json> <new-external-output.zip>
python test_local_adapter.py --output <external-test-temp-directory>
```

No real shader, proprietary recipe, Nostalgia derivative or reconstructed archive is included. Synthetic adapter tests manipulate only the literal own fixture text `original local text` and `// ours`. The engine does not confer rights to any user-supplied input or recipe. Its locally generated result is not authorized for redistribution by this module's MIT notice.

Own source/tooling is licensed by `LICENSE-Motiquies.txt`; original author attribution and upstream exclusions are preserved. Public availability of this source is independent of deployment or game release approval. Removing the standalone compatibility JAR is the module rollback; original dependencies and user settings remain unchanged.

## Public offline build

Requires Python 3.10+ (standard library only) and a Java 21 JDK. Windows was exercised; executable detection also accepts a Linux JDK, but Linux builds have not been verified. Nothing is downloaded. Supply your legally obtained original dependency archives outside this repository. The complete **ordered** compile/runtime classpath is listed by SDK-relative path and SHA-256 in `dependencies.lock.json`; every listed JAR is verified before and after the build, and unlisted JARs are not added to the classpath. This includes the original Minecraft SRG development client/library tree, NeoForge libraries and pinned mod dependencies. The repository does not grant redistribution rights to those inputs.

The SDK layout is explicit in that lock file:

```text
<sdk-root>/game/mods/<pinned mod JARs>
<sdk-root>/original/.minecraft/libraries/<locked Maven-style paths>
```

Create this layout from your own matching development installation. Do not add it to Git. No worlds, account files, configuration or running game are needed. Each build output must be new/empty and outside both this repository and the SDK. Local transformed upstream classes and test logs remain in that external directory and are never included in the candidate JAR.

```powershell
python build.py --sdk-root <external-sdk> --java-home <installed-jdk21> --output <external-empty-output>
```

The package contains only own `org/motiquies` classes, module resources and the applicable own license. ZIP entries use sorted names, a fixed timestamp/mode and uncompressed storage. Two fresh builds with the recorded Java compiler produce byte-identical JARs; `receipt.json` records every input hash, Java/compiler versions, compiler executable hash, source hashes, archive entries and artifact SHA-256. This establishes reproducibility for the tested toolchain and exact lock, not every future Java/Python version. The output is a build candidate; publication of source is separate from game release approval.
