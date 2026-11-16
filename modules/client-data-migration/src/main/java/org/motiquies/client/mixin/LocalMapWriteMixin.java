package org.motiquies.client.mixin;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.biome.Biome;
import org.motiquies.client.LocalMapObservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapWriter;
import xaero.map.region.*;
import xaero.map.biome.BlockTintProvider;
@Mixin(value=MapWriter.class,remap=false)
abstract class LocalMapWriteMixin {
 @Inject(method="writeChunk",at=@At(value="INVOKE",target="Lxaero/map/region/MapTile;setLoaded(Z)V",shift=At.Shift.AFTER),remap=false,require=1)
 private void motiquies$observed(Level world,Registry<Block> blocks,int distance,boolean onlyLoad,Registry<Biome> biomes,OverlayManager overlay,
  boolean loadChunks,boolean updateChunks,boolean ignoreHeightmaps,boolean flowers,boolean detailedDebug,BlockPos.MutableBlockPos mutable,
  BlockTintProvider tint,int caveDepth,int caveStart,int layer,int tileChunkX,int tileChunkZ,int tileChunkLocalX,int tileChunkLocalZ,int chunkX,int chunkZ,MapUpdateFastConfig config,CallbackInfoReturnable<Boolean> callback){
  LocalMapObservations.observe(((MapWriter)(Object)this).getMapProcessor(),world,layer,chunkX,chunkZ,world.getGameTime());
 }
}
