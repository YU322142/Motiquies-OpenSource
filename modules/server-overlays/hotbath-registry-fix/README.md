# hotbath-registry-fix

Independent offline source delta tooling for the exact reviewed Minecraft1.21.1 input. Read LICENSE.txt, NOTICE.txt and UPSTREAM.md before use. Own tool terms: GPL-3.0-only; upstream GPLv3 retained. This module does not contain the original mod, vendor class bytes, assets or a full production roster.

```text
python -B build.py --input-jar <exact-original.jar> --java-home <jdk21-directory> --dependencies <external-dependencies> --upstream-source <original-ExtraEventsRegister.java> --output <new-external-output-directory>
```

Requires Python3.11+ and JDK21.0.10 (tested Eclipse Temurin21.0.10+7). `--sdk` aliases `--dependencies`. Complete ordered used inputs, expected entry hashes and immutable source provenance are in dependencies.lock.json and producer.json. Obtain external dependencies through those reviewed routes; no download is automatic. Original input/dependency/source mismatch fails before output creation. Output must be outside the entire repository (found using publication-manifest.json), outside SDK and initially absent.

The producer performs a local, minimal entry overlay and verifies every other entry content unchanged. It writes the generated JAR only to the external output alongside license/source notices. A deterministic rebuilt container can differ from a historic ZIP container even when every class/resource content matches; consult the new receipt instead of inferring production JAR equality. No original acceptance tests or live game gate are implied by compilation.

The upstream GPLv3 boundary is retained. Full original source plus this delta/build route are needed for corresponding-source purposes before any later binary distribution. This source export does not publish a modified binary or claim new runtime acceptance.
