# worldedit-direction

Source-only offline historical producer. Review LICENSE.txt, NOTICE.md and UPSTREAM.md before use. Original dependency JARs, compiled classes and assets are deliberately external.

Run from any directory (replace external paths):

```powershell
python build.py --input-jar D:/external/input.jar --java-home D:/external/jdk-21.0.10 --dependencies D:/external/sdk --output D:/external/build-worldedit-direction
```

Output must be a new directory outside the entire repository and SDK. Source-root detection uses publication-manifest.json or CANDIDATE-ROOT, walking ancestors. All input/dependency/source SHA256 pins are checked before output creation. The builder does not download dependencies, start a game/server, contact production or run Gradle. It validates target entry hashes and preserves every other entry content. Generated output/receipts/licenses stay outside the source repository.

Validation scope: two fresh offline builds and full per-entry comparison with the historical production reference are recorded outside this module. Runtime QA, historical diagnostic probes and a full upstream source build are pending; they are not supplied or claimed. This module does not change the deployed server. Binary publication is not authorized by its build receipt.
