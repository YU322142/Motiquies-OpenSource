package org.motiquies.client.mixin;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
import java.nio.file.Path;
import org.motiquies.client.NativeMapReloadBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the original MapSyncer JAR intact while replacing its unsafe forced region reload. */
@Mixin(value = MapPacketHandler.class, remap = false)
abstract class MapSyncerReloadMixin {
    @Shadow private static Path lastMwDir;
    @Inject(method = "triggerSingleRegionLoad(Lcom/mapsyncer/client/XaeroMapDataHandler$RegionCoord;IZ)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void motiquies$nativeReload(XaeroMapDataHandler.RegionCoord coord, int caveLayer,
                                              boolean inViewDistance, CallbackInfo callback) {
        NativeMapReloadBridge.enqueue(coord.x(), coord.z(), caveLayer, lastMwDir);
        callback.cancel();
    }
}
