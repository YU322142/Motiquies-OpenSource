package com.mapsyncer.server.mixin;
import com.mapsyncer.server.NativeDirtyMapUpdates;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Covers both vanilla and C2ME raw writes, after the actual MCA/header commit succeeds. */
@Mixin(RegionFile.class)
public abstract class SavedRegionFileMixin {
 @Shadow public abstract Path getPath();
 @Inject(method="write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",at=@At("RETURN"))
 private void motiquies$saved(ChunkPos pos,ByteBuffer data,CallbackInfo ci){NativeDirtyMapUpdates.saved(getPath(),pos);}
 @Inject(method="clear(Lnet/minecraft/world/level/ChunkPos;)V",at=@At("RETURN"))
 private void motiquies$cleared(ChunkPos pos,CallbackInfo ci){NativeDirtyMapUpdates.saved(getPath(),pos);}
}
