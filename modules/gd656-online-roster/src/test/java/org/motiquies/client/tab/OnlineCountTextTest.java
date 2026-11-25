package org.motiquies.client.tab;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.mods.gd656killicon.common.ScoreboardEntry;

public final class OnlineCountTextTest {
    public static void main(String[] args) {
        for (int count : new int[]{0, 1, 121}) {
            var label = OnlineCountText.label(count);
            if (!(label.getContents() instanceof TranslatableContents text)
                    || !text.getKey().equals("hud.motiquies_tab_count.online_players")
                    || text.getArgs().length != 1 || !text.getArgs()[0].equals(count)) {
                throw new AssertionError("The localized label must contain the full count " + count);
            }
        }
        if (OnlineCountText.fitScale(80, 100) != 1.0f || OnlineCountText.fitScale(245, 100) * 245 > 98) {
            throw new AssertionError("The original subtitle scale should shrink only when necessary");
        }
        var visible = UUID.fromString("8495678a-632d-4be1-b427-aaaabbcc0001");
        var newcomer = UUID.fromString("8495678a-632d-4be1-b427-aaaabbcc0002");
        var offline = UUID.fromString("8495678a-632d-4be1-b427-aaaabbcc0003");
        var roster = OnlineScoreboardRows.collect(
                List.of(new OnlineScoreboardRows.Player(visible, "NowVisible", 42),
                        new OnlineScoreboardRows.Player(newcomer, "NewPlayer", 16)),
                List.of(new ScoreboardEntry(offline, "Offline", "Offline", 500, 4, 3, 2, 30),
                        new ScoreboardEntry(visible, "OldName", "OldName", 27, 4, 3, 2, -1)));
        if (roster.entries().size() != 2 || !roster.entries().getFirst().name.equals("NowVisible")
                || roster.entries().getFirst().score != 27 || roster.entries().getFirst().ping != 42
                || !roster.entries().getLast().uuid.equals(newcomer) || !roster.unscored().equals(Set.of(newcomer))) {
            throw new AssertionError("The online list must match live UUIDs and preserve known server scores");
        }
        System.out.println("PASS: localized counts, subtitle width, live roster and server scores");
    }
}
