package org.motiquies.client.tab.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.mods.gd656killicon.client.gui.elements.GDTextRenderer;
import org.mods.gd656killicon.client.gui.tabs.ConfigTabContent;
import org.mods.gd656killicon.client.gui.tabs.ScoreboardTab;
import org.mods.gd656killicon.common.ScoreboardEntry;
import org.motiquies.client.tab.OnlineCountText;
import org.motiquies.client.tab.OnlineScoreboardRows;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScoreboardTab.class, remap = false)
abstract class Gd656ScoreboardMixin extends ConfigTabContent {
    @Shadow private static List<ScoreboardEntry> leaderboardData;
    @Shadow private boolean hideOffline;

    @Unique private static volatile WeakReference<ClientPacketListener> motiquies$scoreboardConnection = new WeakReference<>(null);
    @Unique private List<PlayerInfo> motiquies$previousPlayers = List.of();
    @Unique private List<ScoreboardEntry> motiquies$previousScores = List.of();
    @Unique private OnlineScoreboardRows motiquies$roster = new OnlineScoreboardRows(List.of(), Set.of());

    protected Gd656ScoreboardMixin(Minecraft minecraft, Component title) {
        super(minecraft, title);
    }

    @Inject(method = "updateData", at = @At("RETURN"), require = 1)
    private static void motiquies$associateScores(List<ScoreboardEntry> entries, CallbackInfo ci) {
        motiquies$scoreboardConnection = new WeakReference<>(Minecraft.getInstance().getConnection());
    }

    @Inject(method = "onTabOpen", at = @At("HEAD"), require = 1)
    private void motiquies$openOnlineRoster(CallbackInfo ci) {
        if (minecraft.getConnection() != null && !minecraft.isLocalServer()) {
            hideOffline = true;
        }
    }

    @Inject(method = "renderContent", at = @At("HEAD"), require = 1)
    private void motiquies$refreshOnlineRoster(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                               int left, int top, int right, CallbackInfo ci) {
        var connection = minecraft.getConnection();
        if (!hideOffline || connection == null || minecraft.isLocalServer()) {
            return;
        }
        var players = connection.getListedOnlinePlayers();
        var scores = motiquies$scoreboardConnection.get() == connection ? leaderboardData : List.<ScoreboardEntry>of();
        if (players.size() == motiquies$previousPlayers.size() && players.containsAll(motiquies$previousPlayers)
                && scores.equals(motiquies$previousScores)) {
            return;
        }
        motiquies$previousPlayers = List.copyOf(players);
        motiquies$previousScores = List.copyOf(scores);
        motiquies$roster = OnlineScoreboardRows.collect(motiquies$previousPlayers.stream().map(player -> {
            var profile = player.getProfile();
            return new OnlineScoreboardRows.Player(profile.getId(), profile.getName(), player.getLatency());
        }).toList(), motiquies$previousScores);
    }

    @ModifyExpressionValue(method = {"updateScroll", "renderList", "renderRow"},
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lorg/mods/gd656killicon/client/gui/tabs/ScoreboardTab;leaderboardData:Ljava/util/List;"),
            require = 5, allow = 5)
    private List<ScoreboardEntry> motiquies$useRosterForDisplay(List<ScoreboardEntry> scores) {
        var connection = minecraft.getConnection();
        if (connection == null || minecraft.isLocalServer()) {
            return scores;
        }
        if (hideOffline) {
            return motiquies$roster.entries();
        }
        return motiquies$scoreboardConnection.get() == connection ? scores : List.of();
    }

    @ModifyExpressionValue(method = "renderRow",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"),
            slice = @Slice(from = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lorg/mods/gd656killicon/common/ScoreboardEntry;score:I")),
            require = 4, allow = 4)
    private String motiquies$displayAvailableStats(String number, @Local(index = 12) ScoreboardEntry entry) {
        return hideOffline && motiquies$roster.unscored().contains(entry.uuid) ? "—" : number;
    }

    @Inject(method = "updateSubtitle(III)V", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
            target = "Lorg/mods/gd656killicon/client/gui/tabs/ScoreboardTab;subtitleRenderer:Lorg/mods/gd656killicon/client/gui/elements/GDTextRenderer;"),
            require = 1)
    private void motiquies$showOnlineCount(int left, int top, int right, CallbackInfo ci,
                                          @Local(index = 4) List<GDTextRenderer.ColoredText> subtitle) {
        var connection = minecraft.getConnection();
        if (connection == null || minecraft.isLocalServer()) {
            return;
        }
        int onlinePlayers = connection.getListedOnlinePlayers().size();
        subtitle.add(new GDTextRenderer.ColoredText("  ·  " + OnlineCountText.label(onlinePlayers).getString(), 0xFFFFFF55));
    }

    @Inject(method = "updateSubtitle(III)V", at = @At("RETURN"), require = 1)
    private void motiquies$fitOnlineSubtitle(int left, int top, int right, CallbackInfo ci,
                                            @Local(index = 4) List<GDTextRenderer.ColoredText> subtitle) {
        float scale = 1.0f;
        if (minecraft.getConnection() != null && !minecraft.isLocalServer()) {
            int textWidth = 0;
            for (var segment : subtitle) {
                textWidth += minecraft.font.width(segment.text);
            }
            scale = OnlineCountText.fitScale(textWidth, right - left);
        }
        ((Gd656TextRendererAccessor) subtitleRenderer).motiquies$setFontSize(scale);
    }
}
