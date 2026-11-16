WorldEdit 7.3.8 official file: https://www.curseforge.com/minecraft/mc-mods/worldedit/files/5830452
Named dependency download: https://www.cursemaven.com/curse/maven/worldedit-225608/5830452/worldedit-225608-5830452.jar
SHA256: 5e7752c97876d87411e3760bcc573cc431f43c453722e6959fa7fe54db1b01ca

Full corresponding upstream source/build route: https://github.com/EngineHub/WorldEdit/tree/7d32b456e7c3237a70e19cca129e51b790c33b73 (release tag 7.3.8).
Modified class origin: worldedit-neoforge/src/main/java/com/sk89q/worldedit/neoforge/internal/NeoForgeTransmogrifier.java, upstream Git blob c835ae1ae8fd4e520fe8d914a08e15e99a86862d.
Complete LICENSE.txt Git blob 0e2a5685345eaf9c54de97350e761d389c1872c3.

External SDK: Minecraft 1.21.1 server SRG 1.21.1-20240808.144430, Guava 32.1.2-jre, failureaccess 1.0.1; exact byte pins/order in dependencies.lock.json. The local reference SDK contains NeoForge 21.1.241. Minecraft server SRG is a generated development artifact; provide a legally provisioned SDK containing the exact pinned file. This module accepts the external SDK and does not run Gradle, download, redistribute or regenerate Minecraft binaries. Exact public regeneration of that generated SRG artifact has not been independently reproduced here.
Official NeoForge 21.1.241 installer index: https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.241/ . This is a provisioning route, not a claim that installer regeneration was tested in this review.
Guava upstream dependency: https://repo1.maven.org/maven2/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar
Failureaccess upstream dependency: https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar
JDK: https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.10%2B7 . Build requires the Windows x64 HotSpot JDK 21.0.10+7 distribution. javac version and the complete supplied JDK tree digest are checked before output, so an unreviewed toolchain cannot silently change output. The hash algorithm and reference tree digest are in dependencies.lock.json; installed-file additions/removals also cause rejection. A different platform requires a separately verified toolchain lock.
