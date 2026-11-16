# ZstdNet production frame delta: source candidate

Proposed module: `server-overlays/zstdnet-production-frame-fix`. This source-only candidate preserves the two MIT Java files from [zstdnet-frame-compat](https://github.com/YU322142/zstdnet-frame-compat/tree/5ec97007f09f9de357c2aa7404c6ceb12f6c716d), upstream/fork authors and complete MIT notices. It reproduces the observed executable frame delta closely, but is **not the recovered historical producer or an exact historical production JAR**. No binary publication is approved by this source tree.

## Offline build

Requires the exact Temurin21.0.10+7 Windows x64 JDK and the four external API JARs pinned in dependencies.lock.json (ASM9.8 and Netty buffer/codec/common4.1.97.Final). Supply an ordinary directory containing those files or an existing SDK directory. The builder verifies all source/self pins, API file hashes, JDK executable hashes and the complete JDK tree hash. Neither Minecraft nor an input/output mod JAR is on the Java classpath. Annotation processors, Gradle, auto-downloads, game/native execution and deployment are absent.

```text
python -B build.py --input-jar <official-original.jar> --dependencies <API-directory-or-SDK> --java-home <JDK21-home> --output <new-external-output-directory>
```

Aliases: --input; --sdk-root/--sdk for --dependencies; --jdk for --java-home. Selected --data-root/--data and --compiler-root/--compiler-input-root/--compiler directories are additional read-only boundaries. Every supplied root is protected, even if aliases are repeated. Output must be new, outside the complete source repository (nearest publication-manifest.json ancestor), input data directory, SDK/dependencies, JDK and every selected data/compiler directory. Boundary and existing-output checks run before reading input or lock contents. Wrong input, changed source/self, altered SDK/JDK and existing/forbidden outputs fail closed.

The exact original input is [official CurseForge file8986886](https://www.curseforge.com/minecraft/mc-mods/zstdnet/files/8986886), NeoForge1.21.1 ZstdNet1.4.9, SHA256 `6a53c2eae4e922408e4f5a4715c0282c8b67bcbafbd7131d65196ee1c758785c`. Its fixed official CDN route and four fixed Maven API routes are in the lock. Obtain inputs separately under their original terms; no upstream binary, class, native library, artwork or mod asset is in this source candidate. License text under src/main/resources is the required upstream legal notice, not a runtime asset.

The source tool's raw output is retained. A separate new deterministic container preserves every payload while sorting entries, using1980 timestamps and STORE. Expected candidate SHA256 is `7fbf8518b758da54aee97d18ab0c7432f14a84646cc6c6ebbf3bbb2a8023248d`. This is explicitly **NEW packaging**, not historical ZIP reproduction. Every output includes a source/input/compiler/hash receipt; rerun with a different fresh external output to compare.

## Exact reproduction boundary

Current historical production is SHA256 `e9588d6736c761b30ee1e6e796a4cf6e89de695a6072c4425aa3d7027b907110`. Its old private producer remains missing. Compared with that reference,72 common payloads are raw byte-exact, including the patched Decoder and version metadata. Across56 runtime classes,55 are raw exact; the sole helper raw mismatch is debug-only. Independent full-class diagnosis retained flags/code/structure/StackMap/Signature and all present nondebug attributes, explicitly accounted for constant-pool changes as debug-referenced only, and found no annotations/reflective MethodParameters/unknown attributes in the differing helper. Thus56/56 runtime nondebug classes correspond; this does not waive the raw mismatch or establish real-login behavior.

The old LOCAL-COMPATIBILITY-NOTICE is absent from this candidate; a complete upstream MIT notice and the source tool's own declaration are added. These legal and packaging differences remain visible. No SPDX header, debug information or notice is removed to chase the old SHA. All-payload and whole-historical-JAR equality remain false. This is the current frame delta source candidate, **not** the separate future330 ready/offer wrapper. Krypton remains a distinct source gap and is not built here.

## Licensing and verification limits

MIT applies only to the independent source/tool and original stated copyrights. Preserve LICENSE.txt, THIRD_PARTY_NOTICES.md and the full upstream MIT text. ASM is BSD-3-Clause; Netty is Apache-2.0. External input nested/native dependencies retain their own terms; this tree does not relicense or distribute them. Existing official input bytes were independently obtained and matched, but the extra fresh official Maven artifact/license cross-check for the four actual SDK API JARs was **not executed** because automatic permission review timed out twice. Their actual local bytes and fixed official URLs are pinned; independent official artifact/notice verification remains a gap. No further retries were performed.

No real network/game login acceptance has run. Exact upstream1.4.9 source-to-binary commit/build correspondence, complete nested/native redistribution notices and historical private packaging/source-command recovery remain open. Source review does not approve distributing generated JARs, replacing production, archiving sources, or claiming all historical customizations fully reproduced.
