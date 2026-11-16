package org.motiquies.client.network;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record MotiquiesMapClockPayload(String epoch,String dimension,long tick,int interval) implements CustomPacketPayload {
 public static final Type<MotiquiesMapClockPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("mapsyncer","motiquies_map_clock"));
 public static final StreamCodec<RegistryFriendlyByteBuf,MotiquiesMapClockPayload> STREAM_CODEC=StreamCodec.composite(ByteBufCodecs.STRING_UTF8,MotiquiesMapClockPayload::epoch,ByteBufCodecs.STRING_UTF8,MotiquiesMapClockPayload::dimension,ByteBufCodecs.VAR_LONG,MotiquiesMapClockPayload::tick,ByteBufCodecs.VAR_INT,MotiquiesMapClockPayload::interval,MotiquiesMapClockPayload::new);
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
