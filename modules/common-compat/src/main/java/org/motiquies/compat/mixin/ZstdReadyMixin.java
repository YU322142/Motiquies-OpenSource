package org.motiquies.compat.mixin;

import cn.tohsaka.factory.zstdnet.core.transport.ZstdPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "cn.tohsaka.factory.zstdnet.network.LanCompressionSync$ReadyMessage", remap = false)
public class ZstdReadyMixin {
    @Redirect(method = "lambda$handle$5", at = @At(value = "INVOKE", target =
            "Lcn/tohsaka/factory/zstdnet/core/transport/ZstdPipeline;consumeUpgradeOffer(Lnet/minecraft/network/Connection;)Z"),
            require = 1, remap = false)
    private static boolean motiquies$consumeLiveOffer(Connection connection) {
        if (!connection.isConnected()) {
            return false;
        }
        try {
            return ZstdPipeline.consumeUpgradeOffer(connection);
        } catch (IllegalStateException exception) {
            // The Netty channel can close after isConnected(). Cancel only this closed offer.
            if (!connection.isConnected()
                    && "Minecraft connection channel is unavailable".equals(exception.getMessage())) {
                return false;
            }
            throw exception;
        }
    }
}
