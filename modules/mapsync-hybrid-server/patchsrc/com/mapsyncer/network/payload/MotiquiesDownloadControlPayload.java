package com.mapsyncer.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional, negotiated serverbound controls. No filesystem paths or arbitrary commands. */
public record MotiquiesDownloadControlPayload(int action,int radius) implements CustomPacketPayload {
    public static final Type<MotiquiesDownloadControlPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("mapsyncer","motiquies_download_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf,MotiquiesDownloadControlPayload> STREAM_CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,MotiquiesDownloadControlPayload::action,
            ByteBufCodecs.VAR_INT,MotiquiesDownloadControlPayload::radius,MotiquiesDownloadControlPayload::new);
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
