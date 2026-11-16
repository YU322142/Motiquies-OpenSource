package org.motiquies.shadercompat.mixin;

import com.seibel.distanthorizons.api.DhApi;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Align Iris's current normal-render decision before DH snapshots its pass.
 * The deferred call uses the state selected by the normal call, as upstream does.
 * No constructor, destroy, dimension cache, render cancellation or log filtering.
 */
@Mixin(targets="com.seibel.distanthorizons.core.api.internal.ClientApi", remap=false)
public class DhPassSelectionMixin {
    @Inject(method="renderLodLayer(Z)V", at=@At(value="INVOKE",
        target="Lcom/seibel/distanthorizons/core/render/DhApiRenderProxy;getDeferTransparentRendering()Z"),
        require=1, expect=1, allow=1, remap=false)
    private void motiquies$beforePassSelection(boolean deferred, CallbackInfo callback) {
        if (deferred) return;
        boolean override = Iris.isPackInUseQuick() && Iris.getPipelineManager().getPipeline()
            .map(WorldRenderingPipeline::getDHCompat)
            .map(DHCompat::getInstance)
            .map(value -> ((DHCompatInternal) value).shouldOverride)
            .orElse(false);
        DhApi.Delayed.renderProxy.setDeferTransparentRendering(override);
    }
}
