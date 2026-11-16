# Tom Storage performance — runtime Java source only

This MIT module independently compiles the complete 182 runtime Java files into 290 classes. It preserves performance, inventory de-duplication/recovery, capacities, sorting, session ownership and optional integrations. Read NOTICE.md and SOURCE-ORIGINS.json for original authors and the explicitly new context joins.

It supplies source and hash metadata, not a runnable mod. No runtime resources or images are needed to compile the Java. No mod JAR is produced. All 223 original runtime resources, including 94 PNGs, remain outside this module; three exact PNG rights gaps and the full resource/asset reconstruction remain unresolved.

Provide the exact external SDK, API files and JDK listed in dependencies.lock.json, compiler-profile/profile.json and toolchain.lock.json. ACQUISITION.md gives official routes and supported directory layouts. Nothing is downloaded automatically.

```text
python -B derive_compiler_inputs.py --sdk <SDK> --output <NEW_COMPILER_DIR>
python -B build.py --sdk <SDK> --api <API_DIR> --compiler-input-root <NEW_COMPILER_DIR> --jdk <JDK> --output <NEW_CLASS_BUILD>
```

The SDK derivative step validates parent archives, the official access-rule entry, original Tom AT and exact expected children. It changes only documented access-flag words, including structural InnerClasses flags. All other bytes and method bodies remain unchanged. These derived SDK files stay external.

Outputs must be new directories outside the entire source repository and every supplied SDK/API/compiler/JDK/data/protected input root. This is checked before reading locks, source or dependency contents. Aliases include --sdk-root/--sdk, --java-home/--jdk and --api-input-root/--api. Optional --data-root and --protected-root can be repeated to protect additional inputs. Existing output is never overwritten or re-certified. All source/tool/metadata and 143 dependency hashes are checked before compilation and rechecked afterward; annotation processors and implicit source compilation are disabled.

Each class-only output contains classes/, a complete raw class hash map, comparison and receipt. All 290 names/raw hashes remain compared to the unchanged original 515-entry profile. A separate diagnostic profile excludes only SourceFile, LineNumberTable, LocalVariableTable and LocalVariableTypeTable, preserving the constant pool, InnerClasses and all non-debug bytes. A diagnostic match is never reported as raw equality. The reference result is 279 raw exact plus 11 debug-only differences; any missing/extra/non-debug class difference fails validation.

The class-only scope always reports historical515RawExact=false, fullModPackaged=false and gameplayAcceptance=NOT RUN. Complete historical raw equality, archive/container equality, full assets and gameplay are not established by successful Java compilation.

Three unchanged original pure helper tests are optional:

```text
python -B run_pure_regressions.py --sdk <SDK> --api <API_DIR> --compiler-input-root <NEW_COMPILER_DIR> --jdk <JDK> --class-build <NEW_CLASS_BUILD> --output <NEW_TEST_BUILD>
```

They test generic recipe allocation, inventory selection and null-Level worker/delegate logic. They do not test menus, world bootstrap, component-bearing item stacks or gameplay. Test classes are written separately from the 290 runtime classes. The builder never starts a game/server, runs Gradle or executes a dependency JAR main.
