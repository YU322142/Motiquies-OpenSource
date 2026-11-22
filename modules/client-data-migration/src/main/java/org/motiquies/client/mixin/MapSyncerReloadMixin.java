package org.motiquies.client.mixin;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native source commits own the only renderer handoff. */
@Mixin(value = MapPacketHandler.class, remap = false)
abstract class MapSyncerReloadMixin {
    @Inject(method = "triggerSingleRegionLoad(Lcom/mapsyncer/client/XaeroMapDataHandler$RegionCoord;IZ)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void motiquies$skipLegacyReload(XaeroMapDataHandler.RegionCoord coord, int caveLayer,
                                                  boolean inViewDistance, CallbackInfo callback) {
        callback.cancel();
    }
}
