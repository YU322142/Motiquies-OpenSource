package com.mapsyncer.server;

import com.google.gson.JsonObject;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** NeoForge-only bridge. Game objects are read only from commands/requests/tick on the main thread. */
public final class DownloadController {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Context> WORKER = new ThreadLocal<>();
    private static volatile DownloadPolicy policy;
    private static volatile MinecraftServer currentServer;
    private static volatile boolean pressure;
    private static final Map<UUID,DownloadEstimate> ESTIMATES = new ConcurrentHashMap<>();
    private static final Map<UUID,DownloadControlRate> CONTROL_RATES = new ConcurrentHashMap<>();
    private static long lastStatus;
    private static DownloadPolicy.Pacer aggregate = new DownloadPolicy.Pacer();
    public static final int PLAYER_KIB = 256, TOTAL_KIB = 512, LARGE_SLOTS = 1;

    public static void register() {
        if (!REGISTERED.compareAndSet(false,true)) return;
        NeoForge.EVENT_BUS.addListener(DownloadController::commands);
        NeoForge.EVENT_BUS.addListener(DownloadController::logout);
        NeoForge.EVENT_BUS.addListener(DownloadController::tick);
        NeoForge.EVENT_BUS.addListener(DownloadController::stopped);
    }
    private static synchronized DownloadPolicy ensure(MinecraftServer server) throws IOException {
        if (policy == null || currentServer != server) {
            policy = new DownloadPolicy(FMLPaths.CONFIGDIR.get().resolve("motiquies-map-download/players.properties"));
            currentServer = server; pressure = false; aggregate = new DownloadPolicy.Pacer();
        }
        return policy;
    }
    private static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("motiquiesmap").requires(s -> s.getEntity() instanceof ServerPlayer);
        for (String action : List.of("status","nearby","dimension","global","pause","resume")) {
            root.then(Commands.literal(action).executes(ctx -> command(ctx.getSource().getPlayerOrException(),action)));
        }
        root.then(Commands.literal("custom").then(Commands.argument("radius",IntegerArgumentType.integer(0,DownloadPolicy.MAX_RADIUS))
                .executes(ctx -> command(ctx.getSource().getPlayerOrException(),"custom",IntegerArgumentType.getInteger(ctx,"radius")))));
        event.getDispatcher().register(root);
    }
    private static int command(ServerPlayer player,String command) {
        return command(player,command,-1);
    }
    public static void control(ServerPlayer player,int action,int radius){
        if(action<0||action>5||radius<0||radius>DownloadPolicy.MAX_RADIUS)return;
        if(!CONTROL_RATES.computeIfAbsent(player.getUUID(),id->new DownloadControlRate()).take(System.nanoTime()))return;
        String name=switch(action){case 0->"status";case 1->"custom";case 2->"dimension";case 3->"global";case 4->"pause";default->"resume";};
        command(player,name,radius);
    }
    private static int command(ServerPlayer player,String command,int radius) {
        SUBSCRIBERS.add(player.getUUID());
        try {
            DownloadPolicy p = ensure(player.getServer());
            UUID id = player.getUUID();
            if (command.equals("status")) { reply(player,"status",null); return 1; }
            if (command.equals("custom")) p.selectCustom(id,radius);
            else if (command.equals("resume")) p.resume(id);
            else p.select(id,switch(command) {
                case "nearby" -> DownloadPolicy.Mode.NEARBY;
                case "dimension" -> DownloadPolicy.Mode.DIMENSION;
                case "global" -> DownloadPolicy.Mode.GLOBAL;
                default -> DownloadPolicy.Mode.PAUSED;
            });
            // Invalidate native session and queued sends before the client receives the start ACK.
            p.cancel(id);
            ESTIMATES.remove(id);
            ServerSyncHandlerLogic.onPlayerDisconnect(id);
            NetworkManager.sendToPlayer(player,new SyncProgressPayload(0,0,"aborted_paused"));
            reply(player,command.equals("pause") ? "pause" : "start",null);
            publishQueue(); return 1;
        } catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger(DownloadController.class).warn("Map download preference failed",failure);
            // Never emit start on persistence failure.
            if (policy != null) reply(player,"status","偏好保存失败，请重试");
            else player.sendSystemMessage(Component.literal("地图下载设置暂不可用"));
            return 0;
        }
    }
    private static void reply(ServerPlayer player,String action,String override) {
        DownloadPolicy p = policy;
        if (p == null || !SUBSCRIBERS.contains(player.getUUID())) return;
        var job = p.job(player.getUUID());
        int position = p.position(job);
        DownloadEstimate estimate=job==null?ESTIMATES.get(player.getUUID()):job.estimate;
        var view=estimate==null?new DownloadEstimate.View(-1,-1,-1):estimate.view(effectiveKiB()*1024,System.nanoTime());
        String status = override != null ? override : p.mode(player.getUUID()) == DownloadPolicy.Mode.PAUSED ? "已暂停"
                : position > 0 ? "排队中" : job == null ? (view.remainingBytes()==0?"传输已调度，等待客户端处理":"待命")
                : job.large() && (pressure||p.hasNearby()) ? "服务器繁忙，暂缓大型下载" : view.totalBytes()<0 ? "正在统计差异区域" : "下载中";
        JsonObject json = new JsonObject();
        json.addProperty("version",2); json.addProperty("action",action);
        json.addProperty("mode",p.mode(player.getUUID()).name()); json.addProperty("previousMode",p.previous(player.getUUID()).name());
        json.addProperty("queuePosition",position); json.addProperty("playerKiB",effectiveKiB());
        json.addProperty("totalKiB",TOTAL_KIB); json.addProperty("largeSlots",LARGE_SLOTS);
        json.addProperty("status",status); json.addProperty("notice","按已完成区域续传");
        json.addProperty("radius",p.radius(player.getUUID()));json.addProperty("maxRadius",DownloadPolicy.MAX_RADIUS);
        json.addProperty("totalBytes",view.totalBytes());json.addProperty("remainingBytes",view.remainingBytes());
        json.addProperty("etaSeconds",view.seconds());
        String text = "[MotiquiesMapDownload]"+json;
        if (status.length() > 64 || text.length() > 2048) throw new IllegalStateException("ACK bounds");
        // Vanilla admits overlay system messages even when chat is HIDDEN.
        // The subscribed client cancels this technical message before GUI/narrator display.
        player.sendSystemMessage(Component.literal(text),true);
    }
    private static int effectiveKiB(){int n=PlatformManager.getPlatform().getSyncSpeedLimitKBps();return n<=0?PLAYER_KIB:Math.min(PLAYER_KIB,n);}
    private static void publishQueue() {
        MinecraftServer server = currentServer;
        if (server == null) return;
        server.execute(() -> {
            if (server != currentServer) return;
            for (UUID id : SUBSCRIBERS) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) reply(player,"status",null);
            }
        });
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID(); SUBSCRIBERS.remove(id);
        ESTIMATES.remove(id);
        CONTROL_RATES.remove(id);
        DownloadPolicy p = policy; if (p != null) p.cancel(id);
        ServerSyncHandlerLogic.onPlayerDisconnect(id); publishQueue();
    }
    private static void tick(ServerTickEvent.Post event) {
        if (event.getServer() != currentServer) return;
        double mspt = event.getServer().getAverageTickTimeNanos()/1_000_000.0;
        boolean previous = pressure;
        if (mspt >= 45) pressure = true; else if (mspt <= 35) pressure = false;
        long now=System.nanoTime();
        if (previous != pressure || now-lastStatus>=TimeUnit.SECONDS.toNanos(1)){lastStatus=now;publishQueue();}
    }
    private static synchronized void stopped(ServerStoppedEvent event) {
        if (event.getServer() != currentServer) return;
        if (policy != null) policy.clear();
        SUBSCRIBERS.clear(); policy = null; currentServer = null;
        ESTIMATES.clear();lastStatus=0;
        CONTROL_RATES.clear();
    }
    public static void cancel(UUID id) {
        DownloadPolicy p = policy; if (p != null) { p.cancel(id); publishQueue(); }
    }
    public static Context prepare(ServerPlayer player) throws IOException {
        DownloadPolicy p = ensure(player.getServer());
        if (p.mode(player.getUUID()) == DownloadPolicy.Mode.PAUSED) return null;
        var job=p.begin(player.getUUID());ESTIMATES.put(player.getUUID(),job.estimate);
        return new Context(p,job,player.getServer(),
                DimensionPathMapping.getInstance().toXaeroDimension(DimensionApiHelper.getDimId(player.level().dimension())),
                player.getBlockX() >> 9,player.getBlockZ() >> 9);
    }
    public static boolean paused(ServerPlayer player) {
        try { return ensure(player.getServer()).mode(player.getUUID()) == DownloadPolicy.Mode.PAUSED; }
        catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger(DownloadController.class).warn("Map download policy unavailable; refusing sync",failure);
            return true;
        }
    }
    /** Called on the server thread; update hints obey the player's existing download choice. */
    public static boolean wantsUpdate(ServerPlayer player,DirtyRegionQueue.Key key){
        DownloadPolicy p=policy;
        if(p==null||currentServer!=player.getServer()||!SUBSCRIBERS.contains(player.getUUID()))return false;
        String dimension=DimensionPathMapping.getInstance().toXaeroDimension(DimensionApiHelper.getDimId(player.level().dimension()));
        String changed=DimensionPathMapping.getInstance().toXaeroDimension(key.dimension());
        return DownloadPolicy.allows(p.mode(player.getUUID()),p.radius(player.getUUID()),dimension,
                player.getBlockX()>>9,player.getBlockZ()>>9,changed,key.regionX(),key.regionZ());
    }
    public static Context context() { return WORKER.get(); }
    public static Path snapshotRoot() { Context ctx = WORKER.get(); return ctx == null || ctx.snapshot == null ? null : ctx.snapshot.root(); }
    public static final class Context implements AutoCloseable {
        final DownloadPolicy p; final DownloadPolicy.Job job; final MinecraftServer server;
        public final String dimension; public final int centerX,centerZ;
        private DownloadGenerationGate.Lease fence;
        private DownloadSnapshot snapshot;
        private long lastHeartbeat;
        Context(DownloadPolicy p,DownloadPolicy.Job job,MinecraftServer server,String dim,int x,int z) {
            this.p=p; this.job=job; this.server=server; dimension=dim; centerX=x; centerZ=z;
        }
        public boolean current() { return p.current(job) && !Thread.currentThread().isInterrupted(); }
        public boolean allows(String dim,int x,int z) { return DownloadPolicy.allows(job.mode,job.radius,dimension,centerX,centerZ,dim,x,z); }
        public void total(long bytes){job.estimate.total(bytes,System.nanoTime());publishQueue();}
        private void heartbeat(String reason) {
            long now = System.nanoTime();
            if (now-lastHeartbeat < TimeUnit.SECONDS.toNanos(5)) return;
            lastHeartbeat=now;
            server.execute(() -> {
                if (!p.current(job)) return;
                ServerPlayer player=server.getPlayerList().getPlayer(job.player);
                if (player != null) NetworkManager.sendToPlayer(player,new SyncProgressPayload(0,0,reason));
            });
        }
        private boolean waitForTurn() throws InterruptedException {
            while (current()) {
                if (p.tryAcquire(job)) { publishQueue(); return true; }
                heartbeat("waiting_for_slot"); Thread.sleep(50);
            }
            return false;
        }
        private boolean waitPressure() throws InterruptedException {
            while (current() && job.large() && (pressure || p.hasNearby())) {
                heartbeat("waiting_for_server"); Thread.sleep(50);
            }
            return current();
        }
        /** Called only by native background sync workers before awaitGenerationIdle. */
        public boolean enter() throws InterruptedException {
            WORKER.set(this);
            if (!waitForTurn() || !waitPressure()) return false;
            fence = DownloadGenerationGate.INSTANCE.block(); return current();
        }
        public void capture(int syncVersion) throws IOException {
            if (!job.large()) return;
            var progress=new DownloadHeartbeat(System::nanoTime, () -> server.execute(() -> {
                if (!p.current(job) || !ServerSyncSession.isCurrent(job.player,syncVersion)) return;
                ServerPlayer player=server.getPlayerList().getPlayer(job.player);
                if (player!=null) NetworkManager.sendToPlayer(player,new SyncProgressPayload(0,0,"preparing_snapshot"));
            }),TimeUnit.SECONDS.toNanos(5));
            // Stop the native first-packet retry timer before the first potentially slow link.
            progress.start();
            ServerMapCache.Snapshot layout = ServerMapCache.snapshot();
            if (server.isDedicatedServer() && PublishedMapCache.isEnabled()) {
                layout = new ServerMapCache.Snapshot(PublishedMapCache.currentRoot(),false,Map.of());
            }
            // Integrated Xaero is written by a separate client renderer, outside the native fence.
            if (layout.sharedXaero()) throw new IOException("Large snapshots require the native/public server cache");
            Path live = layout.root().toAbsolutePath().normalize();
            Path owner = live.getParent().resolve(".motiquies-map-download-snapshots");
            snapshot = DownloadSnapshot.capture(live,owner,relative -> {
                String path = relative.toString().replace('\\','/');
                if (!path.matches("[^/]+/(?:caves/-?\\d+/)?-?\\d+_-?\\d+\\.zip")) return false;
                String dim=path.substring(0,path.indexOf('/'));
                String[] coords=path.substring(path.lastIndexOf('/')+1,path.length()-4).split("_");
                try{return allows(dim,Integer.parseInt(coords[0]),Integer.parseInt(coords[1]));}
                catch(NumberFormatException invalid){return false;}
            },() -> { if (!current()) return false; progress.poll(); return true; });
            // No live references are used after capture. Incremental generation can resume now.
            fence.close(); fence=null;
            int repaired=snapshot.repairMissingMetadata(() -> {
                try { if (!waitPressure()) return false; progress.poll(); return current(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
            });
            if (repaired>0) LoggerFactory.getLogger(DownloadController.class).info("Repaired {} missing entries in owned download snapshot",repaired);
        }
        public boolean pace(int bytes) {
            try {
                if (!waitPressure()) return false;
                int kib=effectiveKiB();
                long now=System.nanoTime();
                long deadline=Math.max(job.playerPacer.reserve(bytes,kib*1024,now),aggregate.reserve(bytes,TOTAL_KIB*1024,now));
                while (current() && System.nanoTime() < deadline) Thread.sleep(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
                if(!waitPressure())return false;
                job.estimate.scheduled(bytes,System.nanoTime());
                return true;
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
        }
        public boolean scanReady() {
            try {
                if (!waitPressure()) return false;
                heartbeat("scanning_regions"); return current();
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
        }
        public void close() {
            if (fence != null) { fence.close(); fence=null; }
            if (snapshot != null) try { snapshot.close(); } catch (IOException failure) { LoggerFactory.getLogger(DownloadController.class).warn("Cannot clean owned map snapshot {}",snapshot.root(),failure); }
            p.release(job); WORKER.remove(); publishQueue();
        }
    }
}
