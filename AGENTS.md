# Public maintenance rules

- Read each component's README, license, dependency lock and provenance before editing.
- Keep original authors and module-specific licensing. Never apply the root MIT terms to an upstream component.
- Current gameplay target is NeoForge 1.21.1; legacy tools are separate and do not enter production by default.
- Preserve reproducible input versions, hashes, build commands and output evidence. A matching filename is insufficient provenance.
- Runtime source, build reproducibility and actual game acceptance are separate states; report each accurately.
- Do not commit third-party JARs, extracted vendor bytecode, proprietary shader material, private recipes, worlds, backups, player files, credentials or local machine paths.
- Outputs and SDKs belong outside this repository. Do not relax a dependency check to make a build pass.
- Update the reviewed publication snapshot deliberately after review; do not auto-refresh it to hide changes.
- Public CI never changes production, host networking, or user data.
- The private aggregate retains historical provenance. Synchronize reviewed source changes deliberately; do not silently maintain divergent implementations.
