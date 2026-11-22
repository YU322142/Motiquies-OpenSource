package org.motiquies.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xaero.hud.minimap.config.primary.option.MinimapPrimaryClientConfigOptions;
import xaero.map.WorldMap;
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions;
import xaero.minimap.XaeroMinimap;

@Mod(value = "motiquies_client_migration", dist = Dist.CLIENT)
public final class ClientMigrationMod {
    private static final Logger LOG = LogManager.getLogger();
    private boolean prepared;
    private boolean showIntro;
    private String status = "";
    private String dhStatus = "";

    public ClientMigrationMod(IEventBus modBus) {
        MapDownloadClient.registerPayloads(modBus);
        try {
            Path directory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            var removed = DhCacheMigration.migrate(directory, ServerFiles.knownDhFolders(directory));
            LOG.info("Motiquies native DH upgrade checked before joining: reset {} cache files: {}", removed.size(), removed);
        } catch (IOException | RuntimeException failure) {
            dhStatus = "本服旧远景处理未完成；请关闭同一实例的其他游戏窗口后重启重试。路标和JEI不受此操作影响。";
            LOG.error("Motiquies scoped DH reset deferred; completion is not marked", failure);
        }
        NeoForge.EVENT_BUS.addListener(this::title);
        NeoForge.EVENT_BUS.addListener(this::tick);
        new MapDownloadClient();
        NativeMapReloadBridge.register();
        ShaderResizeRecovery.register();
    }

    public static Optional<Path> sharedJeiPath(Path configDirectory) {
        Minecraft client = Minecraft.getInstance();
        var server = client.getCurrentServer();
        if (server != null && !server.isLan() && client.getConnection() != null && client.getSingleplayerServer() == null) {
            return Optional.of(configDirectory.resolve(Migration.JEI_SHARED));
        }
        return Optional.empty();
    }

    private void title(ScreenEvent.Init.Post event) {
        Minecraft client = Minecraft.getInstance();
        ensurePrepared(client);
        if (!(event.getScreen() instanceof TitleScreen title)) return;
        Path directory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        event.addListener(Button.builder(Component.literal("Motiquies 数据迁移"), button ->
                client.setScreen(new MigrationScreen(title, directory, status)))
                .bounds(Math.max(4, title.width - 144), 6, 140, 20).build());
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        ensurePrepared(client);
        if (!showIntro || !(client.screen instanceof TitleScreen title)) return;
        showIntro = false;
        client.setScreen(new MigrationScreen(title, client.gameDirectory.toPath().toAbsolutePath().normalize(),
                "新安装：可选择旧实例导入，或直接返回主菜单。"));
    }

    private void ensurePrepared(Minecraft client) {
        // QuickPlay can open the connection screen without initializing a title screen.
        // Claim before running migration so nested screen initialization cannot re-enter it.
        if (prepared || client.options == null) return;
        prepared = true;
        prepare(client, client.gameDirectory.toPath().toAbsolutePath().normalize());
        if (!dhStatus.isEmpty()) status = status + " " + dhStatus;
    }

    private void prepare(Minecraft client, Path directory) {
        Migration migration = new Migration(directory);
        try {
            var source = migration.upgradeSource();
            if (source.isPresent()) {
                Path old = source.orElseThrow();
                var preview = migration.preview(old);
                var selection = migration.automaticSelection(preview);
                Set<String> registeredKeys = selection.keys() ? prepareKeyImport(client, directory) : Set.of();
                migration.migrate(old, selection, registeredKeys);
                ServerFiles.merge(directory, selection.servers() ? old : null);
                if (selection.keys()) client.options.load();
                enableSharedXaero();
                migration.markComplete();
                status = "旧实例迁移已完成。";
                return;
            }
            migration.configureXaero();
            ServerFiles.merge(directory, null);
            enableSharedXaero();
            if (!migration.isComplete() && !migration.introSeen()) {
                migration.markIntroSeen();
                showIntro = true;
            }
        } catch (IOException | RuntimeException failure) {
            status = "自动迁移未完成，可从主菜单检查旧实例或重试。";
            LOG.error("Motiquies client migration did not complete", failure);
        }
    }

    static Set<String> prepareKeyImport(Minecraft client, Path directory) {
        if (Files.notExists(directory.resolve("options.txt"), LinkOption.NOFOLLOW_LINKS)) {
            client.options.save();
        }
        return Arrays.stream(client.options.keyMappings).map(KeyMapping::getName).collect(Collectors.toUnmodifiableSet());
    }

    static void enableSharedXaero() {
        if (XaeroMinimap.instance != null && XaeroMinimap.instance.getHudConfigs() != null) {
            var config = XaeroMinimap.instance.getHudConfigs().getClientConfigManager().getPrimaryConfigManager().getConfig();
            if (config != null) config.set(MinimapPrimaryClientConfigOptions.DIFFERENTIATE_BY_SERVER_ADDRESS, false);
        }
        if (WorldMap.INSTANCE != null && WorldMap.INSTANCE.getConfigs() != null) {
            var config = WorldMap.INSTANCE.getConfigs().getClientConfigManager().getPrimaryConfigManager().getConfig();
            if (config != null) config.set(WorldMapPrimaryClientConfigOptions.DIFFERENTIATE_BY_SERVER_ADDRESS, false);
        }
    }
}
