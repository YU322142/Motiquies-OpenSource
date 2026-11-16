package org.motiquies.client;

import com.mapsyncer.client.AutoSyncManager;
import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.ClientSyncWriteQueue;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapSyncerCommandLogic;
import org.motiquies.client.network.MotiquiesDownloadControlPayload;
import org.motiquies.client.network.MotiquiesMapUpdatedPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Minecraft/MapSyncer adapter. All state changes and API calls run on the client thread. */
final class MapDownloadClient {
    private static final Logger LOG = LogManager.getLogger();
    private final MapDownloadController controller = new MapDownloadController();
    private final MapDownloadStartScheduler pendingStart = new MapDownloadStartScheduler();
    private final MapUpdateRefresh refresh = new MapUpdateRefresh();
    private static MapDownloadClient active;

    static void registerPayloads(IEventBus bus){bus.addListener((RegisterPayloadHandlersEvent event)->{
            var registrar=event.registrar("1").optional();
            registrar.playToServer(MotiquiesDownloadControlPayload.TYPE,
                    MotiquiesDownloadControlPayload.STREAM_CODEC,(payload,context)->{});
            registrar.playToClient(MotiquiesMapUpdatedPayload.TYPE,MotiquiesMapUpdatedPayload.STREAM_CODEC,(payload,context)->{
                context.enqueueWork(()->{
                    var client=Minecraft.getInstance();var controls=active;
                    if(controls!=null&&client.getConnection()==context.listener())
                        controls.refresh.receive(client.getConnection(),payload.revision(),controls.controller.downloadAllowed(client.getConnection()),now());
                });
            });
            registrar.playToClient(org.motiquies.client.network.MotiquiesMapClockPayload.TYPE,
                    org.motiquies.client.network.MotiquiesMapClockPayload.STREAM_CODEC,(payload,context)->{
                context.enqueueWork(()->{
                    var connection=Minecraft.getInstance().getConnection();
                    var level=Minecraft.getInstance().level;
                    if(connection==context.listener()&&level!=null&&level.dimension().location().toString().equals(payload.dimension())){
                        MapObservationClock.activate(connection,payload.dimension());
                        MapObservationClock.receive(connection,payload.epoch(),payload.dimension(),payload.tick(),payload.interval(),System.nanoTime());
                    }
                });
            });
    });}

    MapDownloadClient() {
        active=this;
        NeoForge.EVENT_BUS.addListener(this::screen);
        NeoForge.EVENT_BUS.addListener(this::chat);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::logout);
    }

    MapDownloadController controller() { return controller; }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    private void screen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null || client.getSingleplayerServer() != null
                || !ModList.get().isLoaded("mapsyncer")) return;
        event.addListener(Button.builder(Component.literal("服务器地图下载"), button ->
                client.setScreen(new MapDownloadScreen(pause, this)))
                .bounds(Math.max(4, pause.width - 130), 6, 126, 20).build());
    }

    void request(MapDownloadController.Command action) {
        request(action,-1);
    }
    void requestCustom(int radius){request(MapDownloadController.Command.CUSTOM,radius);}
    private void request(MapDownloadController.Command action,int radius){
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        connection(connection);
        if (client.player == null || connection == null || client.getSingleplayerServer() != null) return;
        String command = action==MapDownloadController.Command.CUSTOM ? controller.beginCustom(connection,radius,now()) : controller.begin(connection, action, now());
        if (command == null) return;
        try {
            // Selecting a new range also invalidates the previous in-flight async hash/write session.
            // Completed native region receipts are on disk and are never removed here.
            if (action != MapDownloadController.Command.STATUS) suspend();
            if(action!=MapDownloadController.Command.NEARBY&&((ICommonPacketListener)connection).hasChannel(MotiquiesDownloadControlPayload.TYPE)){
                int code=switch(action){case STATUS->0;case CUSTOM->1;case DIMENSION->2;case GLOBAL->3;case PAUSE->4;case RESUME->5;default->throw new IllegalStateException();};
                PacketDistributor.sendToServer(new MotiquiesDownloadControlPayload(code,action==MapDownloadController.Command.CUSTOM?radius:0));
            }else connection.sendCommand(command);
        } catch (RuntimeException | LinkageError failure) {
            pendingStart.cancel();
            controller.failed();
            LOG.error("Motiquies map download request failed", failure);
        }
    }
    boolean writesComplete(){return !MapPacketHandler.isSyncInProgress()&&!ClientHashManager.isComputingMeta()&&!ClientSyncWriteQueue.hasPendingWrites();}

    private void chat(ClientChatReceivedEvent event) {
        Minecraft client = Minecraft.getInstance();
        var result = controller.receive(client.getConnection(), event.isSystem(), event.getMessage().getString(), now());
        if (result.hidden()) event.setCanceled(true);
        try {
            if (result.stop()) suspend();
            if (result.start()) {
                // clearSyncData invalidates callbacks but does not stop old hash or disk work.
                // Keep auto-sync off until a later tick can actually dispatch this one request.
                AutoSyncManager.cancel();
                pendingStart.arm(client.getConnection());
            }
        } catch (RuntimeException | LinkageError failure) {
            pendingStart.cancel();
            controller.failed();
            AutoSyncManager.cancel();
            LOG.error("Motiquies map download control failed", failure);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        connection(connection);
        controller.tick(now());
        try {
            if (pendingStart.take(connection, controller.downloadAllowed(connection),
                    ClientHashManager.isComputingMeta(), ClientSyncWriteQueue.hasPendingWrites(),
                    client.player == null || MapPacketHandler.isSyncInProgress())) {
                start();
            }
            if(refresh.take(connection,controller.downloadAllowed(connection),
                    client.player==null||!writesComplete(),pendingStart.pending()||!controller.canChoose(),now())){
                AutoSyncManager.markPeriodicSync();
                MapSyncerCommandLogic.executeSyncAll(true);
                LOG.debug("Motiquies saved-region update dispatched after native idle");
            }
        } catch (RuntimeException | LinkageError failure) {
            pendingStart.cancel();
            controller.failed();
            AutoSyncManager.cancel();
            LOG.error("Motiquies deferred map download start failed", failure);
        }
    }

    private void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        connection(null);
    }

    private void connection(Object connection) {
        if (controller.connection() != connection) {pendingStart.cancel();refresh.reset();}
        controller.connection(connection);
    }

    private void suspend() {
        pendingStart.cancel();
        refresh.cancel();
        AutoSyncManager.cancel();
        com.mapsyncer.platform.PlatformManager.getPlatform().setClientAutoSyncEnabled(false);
        AutoSyncManager.stopPeriodicSync();
        MapPacketHandler.clearSyncData();
    }

    private void start() {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (!controller.downloadAllowed(connection)) return;
        com.mapsyncer.platform.PlatformManager.getPlatform().setClientAutoSyncEnabled(true);
        MapSyncerCommandLogic.executeSyncAll(false);
        AutoSyncManager.startTickPeriodicSync(() -> client.execute(() -> {
            if (client.getConnection() == connection && client.player != null
                    && pendingStart.canRunPeriodic(connection, controller.downloadAllowed(connection),
                    ClientHashManager.isComputingMeta(), ClientSyncWriteQueue.hasPendingWrites(),
                    MapPacketHandler.isSyncInProgress())) {
                AutoSyncManager.markPeriodicSync();
                MapSyncerCommandLogic.executeSyncAll(true);
            }
        }));
    }
}
