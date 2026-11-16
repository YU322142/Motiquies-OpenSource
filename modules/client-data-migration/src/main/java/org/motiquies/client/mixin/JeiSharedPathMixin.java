package org.motiquies.client.mixin;

import java.nio.file.Path;
import java.util.Optional;
import org.motiquies.client.ClientMigrationMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "mezz.jei.common.util.ServerConfigPathUtil", remap = false)
abstract class JeiSharedPathMixin {
    @Inject(method = "getWorldPath", at = @At("HEAD"), cancellable = true)
    private static void motiquies$useSharedData(Path configDirectory, CallbackInfoReturnable<Optional<Path>> callback) {
        Optional<Path> path = ClientMigrationMod.sharedJeiPath(configDirectory);
        if (path.isPresent()) callback.setReturnValue(path);
    }
}
