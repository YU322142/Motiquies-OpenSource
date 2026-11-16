package org.motiquies.client.mixin;
import org.motiquies.client.LocalMapObservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;
@Mixin(value=MapSaveLoad.class,remap=false)
abstract class LocalMapSaveMixin {
 @Inject(method="saveRegion",at=@At("RETURN"),remap=false,require=1)
 private void motiquies$saved(MapRegion region,boolean log,int attempts,CallbackInfoReturnable<Boolean> callback){
  LocalMapObservations.saved((MapSaveLoad)(Object)this,region);
 }
}
