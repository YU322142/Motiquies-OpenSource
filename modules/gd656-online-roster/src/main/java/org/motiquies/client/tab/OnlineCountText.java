package org.motiquies.client.tab;

import net.minecraft.network.chat.Component;

public final class OnlineCountText {
    private OnlineCountText() {}

    public static Component label(int onlinePlayers) {
        return Component.translatable("hud.motiquies_tab_count.online_players", onlinePlayers);
    }

    public static float fitScale(int textWidth, int availableWidth) {
        return Math.min(1.0f, Math.max(1, availableWidth - 2) / (float) Math.max(1, textWidth));
    }
}
