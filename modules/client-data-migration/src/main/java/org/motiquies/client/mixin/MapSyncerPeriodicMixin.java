package org.motiquies.client.mixin;

import com.mapsyncer.client.AutoSyncManager;
import com.mapsyncer.platform.PlatformManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import org.motiquies.client.network.MotiquiesMapUpdatedPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AutoSyncManager.class, remap = false)
abstract class MapSyncerPeriodicMixin {
    @Shadow
    private static void cancelPeriodic() {
        throw new AssertionError();
    }

    @Inject(method = "startTickPeriodicSync", at = @At("HEAD"), cancellable = true)
    private static void motiquies$useServerNotices(Runnable action, CallbackInfo callback) {
        if (motiquies$hasUpdateNotices()) {
            cancelPeriodic();
            callback.cancel();
        }
    }

    @Inject(method = "getStatusKey", at = @At("HEAD"), cancellable = true)
    private static void motiquies$describeUpdateNotices(int intervalMinutes, CallbackInfoReturnable<Object[]> callback) {
        if (motiquies$hasUpdateNotices() && PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            callback.setReturnValue(new Object[]{"motiquies.map.status.changed_regions"});
        }
    }

    @Unique
    private static boolean motiquies$hasUpdateNotices() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null
                && ((ICommonPacketListener) connection).hasChannel(MotiquiesMapUpdatedPayload.TYPE);
    }
}
