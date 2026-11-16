"""Build the Motiquies overlay against the unmodified NeoForge beta2 artifact."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parent
UPSTREAM_SHA256 = "7e96f19dc703fe1a8c22b7deea8144e2b69c3632b3afc591d2bb84024f8c184a"


def read_java(source: Path, name: str) -> str:
    return (source / name).read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise ValueError("Unexpected upstream source layout: " + old.splitlines()[0])
    return source.replace(old, new, 1)


def build_sources(upstream: Path, output_root: Path) -> list[Path]:
    src = output_root / "generated-sources"
    src.mkdir(parents=True, exist_ok=True)
    common = "libs/common/src/main/java/com/mapsyncer"
    handlers = {
        "NeoForgeNetworkHandler": read_java(upstream, "mc-1.21.1/neoforge/src/main/java/com/mapsyncer/network/impl/NeoForgeNetworkHandler.java"),
        "XaeroMapIntegrator": read_java(upstream, common + "/client/XaeroMapIntegrator.java"),
        "ClientHashManager": read_java(upstream, common + "/client/ClientHashManager.java"),
        "XaeroMapDataHandler": read_java(upstream, common + "/client/XaeroMapDataHandler.java"),
    }
    handlers["NeoForgeNetworkHandler"] = replace_once(
        handlers["NeoForgeNetworkHandler"],
        "        PacketDistributor.sendToPlayer(player,\n            new NeoForgePayloadAdapters.NeoForgeServerInstalledPayload(payload));",
        """        if (!player.connection.hasChannel(NeoForgePayloadAdapters.NeoForgeServerInstalledPayload.TYPE)) return;
        try {
            PacketDistributor.sendToPlayer(player,
                new NeoForgePayloadAdapters.NeoForgeServerInstalledPayload(payload));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(NeoForgeNetworkHandler.class)
                    .debug("Optional MapSyncer notice could not be sent", failure);
        }""",
    )
    handlers["XaeroMapIntegrator"] = replace_once(
        handlers["XaeroMapIntegrator"],
        "    private static Path resolveWorldDirectory(Path worldMapDir, String serverIP) {\n",
        """    private static Path resolveWorldDirectory(Path worldMapDir, String serverIP) {
        Minecraft client = Minecraft.getInstance();
        if (!client.hasSingleplayerServer() && client.getCurrentServer() != null
                && !client.getCurrentServer().isRealm()
                && MotiquiesRegionPolicy.usesSharedDirectory(client.gameDirectory.toPath())) {
            return client.gameDirectory.toPath().resolve("xaero/world-map")
                    .resolve(MotiquiesRegionPolicy.SHARED_FOLDER);
        }
""",
    )
    handlers["ClientHashManager"] = replace_once(
        handlers["ClientHashManager"],
        "        if (cached != null) {\n            return Math.max(cached.timestampSeconds(), fileTs);\n        }\n        return fileTs;",
        """        if (cached != null) {
            Path current = zipPath;
            while (current != null && current.getFileName() != null) {
                if (MotiquiesRegionPolicy.SHARED_FOLDER.equals(current.getFileName().toString())) {
                    // The receipt identifies the public snapshot. Exploration changes disk mtime.
                    return cached.timestampSeconds();
                }
                current = current.getParent();
            }
            return Math.max(cached.timestampSeconds(), fileTs);
        }
        return fileTs;""",
    )
    handlers["XaeroMapDataHandler"] = replace_once(
        handlers["XaeroMapDataHandler"],
        "            moveReplacing(tempFile, outputFile);\n",
        """            if (serverDir.getFileName().toString().equals(MotiquiesRegionPolicy.SHARED_FOLDER)) {
                MotiquiesRegionPolicy.preserveBeforeReplace(outputFile, chunk.data);
            }
            moveReplacing(tempFile, outputFile);
""",
    )
    for name in ("RegionScanner", "GenerationCache", "PlayerJoinHandlerLogic",
                 "ServerSyncHandlerLogic", "CacheCommandHandler"):
        handlers[name] = read_java(upstream, common + "/server/" + name + ".java")
    handlers["RegionScanner"] = replace_once(
        handlers["RegionScanner"], "        DimensionPathMapping mapping = DimensionPathMapping.getInstance();\n        String dimId = DimensionApiHelper.getDimId(dimensionKey);",
        """        worldRoot = PublishedMapCache.converterWorld(worldRoot);
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        String dimId = DimensionApiHelper.getDimId(dimensionKey);""")
    handlers["RegionScanner"] = replace_once(
        handlers["RegionScanner"], "            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);",
        """            Path worldRoot = PublishedMapCache.converterWorld(
                    level.getServer().getWorldPath(LevelResource.ROOT));""")
    handlers["GenerationCache"] = replace_once(
        handlers["GenerationCache"], "    private final Path cacheFile;",
        """    private final Path cacheFile;
    private static volatile GenerationCache publishedInstance;

    public static GenerationCache forPublished(Path cacheDir) {
        Path target = cacheDir.resolve("generation_cache.properties");
        GenerationCache found = publishedInstance;
        if (found != null && target.equals(found.cacheFile)) return found;
        synchronized (GenerationCache.class) {
            found = publishedInstance;
            if (found == null || !target.equals(found.cacheFile)) {
                found = new GenerationCache(cacheDir);
                publishedInstance = found;
            }
            return found;
        }
    }""")
    handlers["GenerationCache"] = replace_once(
        handlers["GenerationCache"], "        if (cache.size() <= MAX_CACHE_REGIONS) {",
        """        if (PublishedMapCache.isConverter() || cache.size() <= MAX_CACHE_REGIONS) {""")
    handlers["CacheCommandHandler"] = replace_once(
        handlers["CacheCommandHandler"], "        return server != null;",
        "        return server != null && (!server.isDedicatedServer() || !PublishedMapCache.isEnabled());")
    handlers["CacheCommandHandler"] = handlers["CacheCommandHandler"].replace(
        "        server.saveEverything(false, true, true);",
        "        if (!PublishedMapCache.isConverter()) server.saveEverything(false, true, true);")
    handlers["PlayerJoinHandlerLogic"] = replace_once(
        handlers["PlayerJoinHandlerLogic"],
        "        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();",
        """        if (server.isDedicatedServer() && PublishedMapCache.isEnabled()) {
            int ticks = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
            String publishedName = PlatformManager.getServerName();
            java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    GenerationCache.forPublished(PublishedMapCache.currentRoot()).getLastGenerationTime())
                    .thenAccept(version -> server.execute(() -> {
                        if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
                            NetworkManager.sendToPlayer(player, new ServerInstalledPayload(getModVersion(), version,
                                    AutoSyncConfig.computeInterval(UpdateMode.TICK, ticks),
                                    UpdateMode.TICK, ticks, publishedName));
                        }
                    }));
            return;
        }
        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();""")
    handlers["ServerSyncHandlerLogic"] = replace_once(
        handlers["ServerSyncHandlerLogic"],
        """        ServerMapCache.Snapshot cacheLayout = ServerMapCache.snapshot();
        Path cacheDir = cacheLayout.root();
        GenerationCache genCache = cacheLayout.sharedXaero() ? null : GenerationCache.getInstance(cacheDir);
        if (genCache != null) genCache.pruneInvalidEntries(cacheDir);""",
        """        final boolean published = server.isDedicatedServer() && PublishedMapCache.isEnabled();
        ServerMapCache.Snapshot cacheLayout = published
                ? new ServerMapCache.Snapshot(PublishedMapCache.currentRoot(), false, Map.of())
                : ServerMapCache.snapshot();
        Path cacheDir = cacheLayout.root();
        GenerationCache genCache = cacheLayout.sharedXaero() ? null :
                (published ? GenerationCache.forPublished(cacheDir) : GenerationCache.getInstance(cacheDir));
        if (genCache != null && !published) genCache.pruneInvalidEntries(cacheDir);""")
    handlers["ServerSyncHandlerLogic"] = replace_once(
        handlers["ServerSyncHandlerLogic"], "if (serverMeta != null && genCache != null) {",
        "if (serverMeta != null && genCache != null && !published) {")
    handlers["ServerSyncHandlerLogic"] = replace_once(
        handlers["ServerSyncHandlerLogic"], "                        if (serverMeta == null) {",
        "                        if (serverMeta == null) {\n                            if (published) return;")
    handlers["ServerSyncHandlerLogic"] = replace_once(
        handlers["ServerSyncHandlerLogic"], "        if (genCache != null) genCache.save();",
        "        if (genCache != null && !published) genCache.save();")
    from policy_patch import apply_policy
    apply_policy(handlers, upstream)
    result = [p for p in (ROOT / "patchsrc").rglob("*.java") if "client" not in p.parts]
    for name, data in handlers.items():
        path = src / (name + ".java")
        path.write_text(data, encoding="utf-8", newline="\n")
        result.append(path)
    return result


def dependency_jars(game: Path) -> list[Path]:
    library = game / "libraries"
    paths = [
        library / "net/neoforged/neoforge/21.1.241/neoforge-21.1.241-client.jar",
        library / "net/neoforged/neoforge/21.1.241/neoforge-21.1.241-universal.jar",
        library / "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar",
        library / "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
        library / "net/neoforged/bus/8.0.5/bus-8.0.5.jar",
        library / "net/neoforged/fancymodloader/loader/4.0.43/loader-4.0.43.jar",
        library / "net/neoforged/mergetool/2.0.0/mergetool-2.0.0-api.jar",
        library / "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
        library / "com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar",
        library / "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
        library / "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar",
    ]
    paths.extend((library / "io/netty").rglob("*.jar"))
    paths.extend((library / "net/fabricmc/sponge-mixin").rglob("*.jar"))
    for path in paths:
        if not path.is_file():
            raise FileNotFoundError(path)
    return paths


def main() -> None:
    import runpy
    runpy.run_path(str(ROOT/'build.py'),run_name='__main__')

if __name__=='__main__':
    import sys
    sys.dont_write_bytecode=True
    main()
