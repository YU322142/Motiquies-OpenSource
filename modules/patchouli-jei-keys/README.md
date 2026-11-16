# Patchouli JEI runtime key mappings

Separate client compatibility candidate for Minecraft1.21.1 / NeoForge21.1.241, Patchouli1.21.1-92-NEOFORGE and JEI19.44.0.403. Source publication does not approve deployment or general release. The coordinating E40 isolated-client checks passed the book recipe/usage R/U scenario; native rebinding, scancodes, inactive contexts, unbound mappings, runtime replacement and absent-JEI gameplay remain separate boundaries requiring acceptance.

The pinned Patchouli plugin resolves two final raw `KeyMapping` fields in its static initializer. In the captured QA startup they are not registered yet, so both fields stay null even after `onRuntimeAvailable`. Its book recipe/use handler then cannot handle the user's configured shortcuts. The key names themselves are unchanged.

This module intercepts that single existing book-key handler. Once JEI provides its runtime, every call obtains the current official `IJeiRuntime.getKeyMappings()` and tests `getShowRecipe()` / `getShowUses()` through `isActiveAndMatches(InputConstants.getKey(keyCode,scanCode))`. A matched key uses the same official focus factory and recipe GUI APIs with the original hovered stack, preserving recipe-first precedence and OUTPUT/INPUT roles. There is one GUI call per handled input and no duplicate event forwarding, key registration, hardcoded R/U mapping, global remap, reflection into JEI internals, or search mutation.

Before readiness, nonmatching/inactive/unbound input returns false. The added native `onRuntimeUnavailable` lifecycle hook releases the obsolete runtime; a later `onRuntimeAvailable` remains original and provides the replacement. Unknown runtime, matcher or GUI exceptions are not caught or hidden. JEI remains an optional dependency; a loading-mod-list presence gate leaves the dependent target untouched when JEI is absent. An unexpectedly unavailable loader inventory remains an explicit error.

The original class initializer and warning call are unchanged, including `Could not locate JEI keybindings, lookups in books may not work`. The compatibility handler no longer relies on those early fields, but this bounded functional fix does not filter logs or rewrite unrelated diagnostics. The warning alone therefore cannot determine whether shortcut handling works after runtime availability.

## Pinned inputs and build

| Original JAR | SHA-256 |
|---|---|
| Patchouli-1.21.1-92-NEOFORGE.jar | `579f23933f1db9569f35f7770b9f2b0b6592a9389d7b3411272487bf5c77c98d` |
| jei-1.21.1-neoforge-19.44.0.403.jar | `31890ccbaf93cf90e2816b8f7cfb5a1340fe20bd8a1680a84e0050f7e07769bd` |

The baseline verifies the known original failure and succeeds only when that expected failure is reproduced; it never packages a candidate:

```powershell
python build.py --sdk-root <external-sdk> --java-home <installed-jdk21> --output <external-baseline-output> --baseline
```

## Evidence and limits

The baseline executes the **original pinned Patchouli plugin bytes** with an empty early key-map fixture, reproducing the actual initialization ordering. JEI interfaces and Minecraft input-key conversion remain original. The item-stack boundary is a minimal fixture so item registries are not initialized; runtime GUI/focus/mapping services are controlled implementations of the actual public JEI interfaces. This verifies the compatibility adapter's dispatch contract, not actual book rendering or a live GUI.

Candidate verification uses the installed Mixin0.8.7 transformer on the exact original plugin bytes, then executes those transformed bytes in the same contracts. Only the original `handleRecipeKeybind` changes. Existing UID, subtype registration, runtime-available method, final fields and eager warning remain unchanged; the unavailable hook and injection callback are the only added methods. An absent-JEI weaving run refuses any JEI dependency resolution and requires unchanged target bytes.

Execution contracts cover readiness, shutdown, replacement runtime, current user rebinding, scancode conversion, inactive/unbound keys, unrelated input, simultaneous recipe/use matches, original hovered-item identity, recipe/use roles, exactly one GUI call and genuine exception propagation. Test-only loader-presence and item-stack fixtures are excluded from the packaged JAR. Production compilation uses actual pinned libraries, not these fixtures. Offline Mixin host adapters retain their origin in the existing Motiquies test host.

## Required isolated client acceptance

Keep the original Patchouli/JEI JARs. Install this separate candidate only in an isolated client with the exact inputs, then restart under the process owner's control. Open a genuine Patchouli book page containing a hovered item; exercise the current JEI recipe and usage shortcuts, verify the correct original item opens once, return to the book, and confirm unrelated keys remain normal. Rebind a shortcut through native controls and verify the new binding works while the old binding does not. Exercise native modifiers/inactive contexts and unbound keys. Test JEI runtime stop/restart or world reconnection for obsolete-runtime rejection, and separately test a client without JEI for normal book startup and no compatibility mixin application. Retain genuine errors and the unchanged startup warning in evidence.

Public source build preparation runs no game or GUI and changes no instance. E40 acceptance above is a separate, bounded client observation. Removing the standalone compatibility JAR rolls back this module; original binaries and user key settings remain intact.

This independent implementation is MIT licensed. Patchouli remains Vazkii's work and JEI remains mezz/contributors' work under their original licenses; no third-party classes, book content, icons, resources or licenses are redistributed inside this candidate. All original JARs remain byte-for-byte intact.

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
