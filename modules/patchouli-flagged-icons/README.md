# Patchouli flagged entry icons

First-party client compatibility source for Minecraft 1.21.1, NeoForge 21.1.241 and Patchouli 1.21.1-92-NEOFORGE. Source publication does not approve deployment or general release. The coordinating E40 isolated-client checks passed the guide-icon scenario; this is not acceptance of every reload/provider/configuration boundary.

The shipped Patchouli `BookContentsBuilder.loadEntry` constructs a `BookEntry` (and parses its icon) before `entry.canAdd()` checks its mod/config flag. Disabled integrations therefore try to resolve missing optional items despite already having correct flags. The 2026-11-16 QA log contains 25 distinct Modular Golems icons and four Maid Restaurant/Bakeries icons of this form. Each original English entry was inspected and already had the matching optional mod flag.

The client mixin applies the exact existing empty-flag / `PatchouliConfig.getConfigFlag` decision after localized JSON is loaded and before constructing an entry. Disabled entries retain the upstream `null` load result. Enabled entries use every original parse, validation and diagnostic path. No replacement icons, JSON rewrites, registry mutations, log filters or upstream JAR changes are involved. On book/resource reload the flag is evaluated again. Categories and templates are unchanged.

This does not fix custom recipe type registration, recipe-book categories, JEI keybindings or Umapyoi missing recipes. The broader private operational investigation is outside this public module.

The baseline command verifies the known original disabled-entry failure and exits successfully only when that expected failure is reproduced. It never packages a candidate:

```powershell
python build.py --sdk-root <external-sdk> --java-home <installed-jdk21> --output <external-baseline-output> --baseline
```

Verification compiles production sources against real pinned libraries, uses the installed Mixin 0.8.7 transformer on the exact original `BookContentsBuilder` bytes, checks that only `loadEntry` changes and that the gate precedes entry allocation, exercises the actual injection callback, and tests disabled/enabled/unflagged parser boundaries. The parser fixture is necessary because the real item parser initializes the game registries; these tests do not establish an actual game registry reload result. Offline host adapters retain their origin in the existing Motiquies Tom/JEI test host.

Required client QA: install only in an isolated client candidate; open the Modular Golems and Maid Restaurant guides with providers absent, reload resources, reconnect, and confirm disabled pages stay absent and these 29 premature icon warnings disappear. Confirm enabled pages, icons and links remain present. With the appropriate optional provider or an existing config flag enabled in a separate controlled fixture, verify its entry appears. Preserve genuine required-icon warnings and all recipe/JEI diagnostics. Remove this standalone compatibility JAR to roll back; original JARs remain unchanged.

No third-party classes, book text, icons, assets or licenses are redistributed by this module. Patchouli remains Vazkii's work under its original CC BY-NC-SA 3.0 declaration; this separate compatibility implementation is MIT licensed. Existing author metadata and all third-party files remain untouched.

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
