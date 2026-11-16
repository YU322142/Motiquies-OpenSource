# common-compat

Own runtime compatibility source for Minecraft1.21.1 / NeoForge21.1.241.
License: MIT; full terms in LICENSE.txt. Original dependency JARs are external inputs and retain their own licenses.

Build with Python3.11+ and Eclipse Temurin JDK21.0.10+7 (javac21.0.10):

```text
python -B build.py --sdk <external-dependency-directory> --jdk <jdk21-directory> --output <new-external-output-directory>
```

Supply every exact file listed in dependencies.lock.json beneath the external SDK directory, in any subdirectories. Obtain dependencies through their official distributors and the legally maintained patched-source projects where applicable. Names, full SHA256 hashes and classpath order are explicit in the lock. This builder never downloads dependencies. Extra SDK files are ignored; only locked SHA-matching files enter the compiler classpath. An exact target JAR may also serve as an identity guard when the source targets it by class name. Missing or mismatched inputs fail before output creation. Use a new output directory; existing outputs are refused.

The JAR contains only the explicit own class/resource allowlist and license notices. Runtime Java sources preserve the audited frozen source bytes. License metadata and packaged notices change the artifact SHA. No vendor payload, loot, recipes, decompiled class bodies, probes, QA actors or game state are included. No deployment or automatic installation occurs.

Compilation and deterministic packaging are the public build gate. Omitted original checks remain PENDING for this public artifact: original private Jade/Zstd native-body callback tests, actual Mixin weaving and real registry/game checks. Previous private evidence is not a new run. No game acceptance is claimed; releaseEligible remains false until independent review and actual acceptance. Build receipts record behaviorTestsRun=false and actualGameAcceptance=PENDING.
