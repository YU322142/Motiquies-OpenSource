package org.motiquies.client.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Revision hint only, not map data or a filesystem path. */
public record MotiquiesMapUpdatedPayload(long revision) implements CustomPacketPayload {
 public static final Type<MotiquiesMapUpdatedPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("mapsyncer","motiquies_map_updated"));
 public static final StreamCodec<RegistryFriendlyByteBuf,MotiquiesMapUpdatedPayload> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.VAR_LONG,MotiquiesMapUpdatedPayload::revision,MotiquiesMapUpdatedPayload::new);
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
