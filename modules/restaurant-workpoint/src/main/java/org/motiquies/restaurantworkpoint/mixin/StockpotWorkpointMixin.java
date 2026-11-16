package org.motiquies.restaurantworkpoint.mixin;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.cooktask.StockpotCookTask;
import com.mastermarisa.maid_restaurant.data.TagBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Add native chair eligibility to the existing stockpot candidate predicate only. */
@Mixin(value=StockpotCookTask.class,remap=false)
public abstract class StockpotWorkpointMixin {
    @Redirect(method="searchWorkBlock(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;II)Lnet/minecraft/core/BlockPos;",
        at=@At(value="INVOKE",target="Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;",remap=false),
        require=1,expect=1,allow=1,remap=false)
    private Stream<BlockPos> motiquies$eligibleFilter(Stream<BlockPos> candidates,Predicate<BlockPos> nativePredicate,
            ServerLevel level,EntityMaid maid,int horizontalSearchRange,int verticalSearchRange) {
        return candidates.filter(pos -> nativePredicate.test(pos) && motiquies$hasNativeChair(level,maid,pos));
    }

    @Unique
    private static boolean motiquies$hasNativeChair(ServerLevel level,EntityMaid maid,BlockPos pot) {
        for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
            BlockPos chair=pot.below().relative(side);
            // Inspect only a presently loaded full chunk; never request/load/generate one.
            if(level.getChunkSource().getChunkNow(chair.getX()>>4,chair.getZ()>>4)==null)continue;
            if(!level.getBlockState(chair).is(TagBlock.SIT_BLOCK))continue;
            List<EntityMaid> occupants=level.getEntitiesOfClass(EntityMaid.class,new AABB(chair));
            if(occupants.isEmpty() || (occupants.size()==1 && occupants.getFirst().equals(maid)))return true;
        }
        return false;
    }
}
