package org.motiquies.client.mixin;
import java.io.*;
import java.util.HashMap;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapBlock;
@Mixin(value=MapSaveLoad.class,remap=false)
public interface NativeMapSaveAccess {
 @Accessor("regionSavePalette") HashMap<?,?> motiquies$blockPalette();
 @Accessor("regionSaveBiomePalette") HashMap<?,?> motiquies$biomePalette();
 @Invoker("savePixel") void motiquies$savePixel(MapBlock block,DataOutputStream out,Registry<Biome> biomes)throws IOException;
}
