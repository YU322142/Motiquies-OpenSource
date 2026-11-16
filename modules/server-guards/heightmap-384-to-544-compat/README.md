# heightmap-384-to-544-compat

Minecraft1.21.1 / NeoForge21.1.241 own runtime compatibility source. License: MIT; see LICENSE.txt and PROVENANCE.json for exact own source repository/commit, original authors and observed production artifact identity. No ownership is inferred from metadata alone: the exported code is bounded own helper/event/mixin/API adapter code; upstream classes remain external dependencies. Create chute guard additionally retains the original Create code MIT notice.

```text
python -B build.py --sdk <external-dependency-directory> --jdk <jdk21-directory> --output <new-external-output-directory>
```

Requires Python3.11+ and JDK21.0.10 (tested with Eclipse Temurin21.0.10+7). Supply the exact legally obtained external JARs listed in dependencies.lock.json; subdirectory layout is flexible. The lock pins every used compiler classpath input by SHA256 and ordered filename. Embedded Create dependencies additionally pin the outer JAR, embedded path and embedded content SHA; extraction occurs only in external temporary build output and no dependency is packaged. Official SDK generation and patched-dependency build routes belong to the repository dependency guide. No download or game launch occurs. Missing/mismatched inputs fail before output creation. Outputs must be new, outside this source module and outside SDK.

The source export preserves audited runtime Java bytes. The compiler uses `-g --release21 -proc:none`; exact production class comparison and deterministic build results are recorded separately by the publication audit. The public JAR SHA changes when license notices, metadata expansion, packaging or excluded resources differ. Never infer byte-identical production reproduction from successful compilation.

No diagnostic actors, tests that extract upstream bodies, javap output, worlds or vendor JARs are included. Original private behavior/weave/migration acceptance suites were not exported or run for this public artifact; those checks remain PENDING. Compilation and class comparison do not establish a new game acceptance. The GameRulesLoadProbeMixin/LoadProbe names in the waypoint module implement runtime rule-load/migration state, not QA probes, and remain necessary runtime code.
