package org.motiquies.client.mixin;

import com.mapsyncer.client.ClientSyncWriteQueue;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import java.nio.file.Path;
import org.motiquies.client.NativeMapWriteBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientSyncWriteQueue.class, remap = false)
abstract class MapSyncerWriteMixin {
    @Inject(method = "submit", at = @At("HEAD"), remap = false)
    private static void motiquies$captureWrite(ChunkMapData chunk, Path serverDir, int worldId,
            com.mapsyncer.client.ClientTimestampCache cache, boolean integratedHost, String selectedMultiworld,
            java.util.function.Consumer<XaeroMapDataHandler.RegionWriteResult> callback, CallbackInfo info) {
        NativeMapWriteBridge.capture(chunk,cache);
    }

    @Redirect(method = "lambda$submit$1", at = @At(value = "INVOKE",
            target = "Lcom/mapsyncer/client/XaeroMapDataHandler;writeChunkData(Lcom/mapsyncer/network/payload/ChunkMapData;Ljava/nio/file/Path;IZLjava/lang/String;)Lcom/mapsyncer/client/XaeroMapDataHandler$RegionWriteResult;"), remap = false)
    private static XaeroMapDataHandler.RegionWriteResult motiquies$nativeCommit(ChunkMapData chunk, Path serverDir,
            int worldId, boolean integratedHost, String selectedMultiworld) {
        return NativeMapWriteBridge.write(chunk, serverDir, worldId, integratedHost, selectedMultiworld);
    }
}
