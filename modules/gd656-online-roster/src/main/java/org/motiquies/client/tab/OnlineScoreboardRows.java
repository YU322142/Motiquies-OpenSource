package org.motiquies.client.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.mods.gd656killicon.common.ScoreboardEntry;

public record OnlineScoreboardRows(List<ScoreboardEntry> entries, Set<UUID> unscored) {
    public record Player(UUID uuid, String name, int ping) {}

    public static OnlineScoreboardRows collect(List<Player> players, List<ScoreboardEntry> scores) {
        var remaining = new HashMap<UUID, Player>();
        for (var player : players) {
            remaining.put(player.uuid(), player);
        }
        var entries = new ArrayList<ScoreboardEntry>(players.size());
        for (var score : scores) {
            var player = remaining.remove(score.uuid);
            if (player != null) {
                entries.add(new ScoreboardEntry(player.uuid(), player.name(), player.name(), score.score,
                        score.kill, score.death, score.assist, Math.max(0, player.ping())));
            }
        }
        remaining.values().stream().sorted(Comparator.comparing(Player::name).thenComparing(Player::uuid))
                .forEach(player -> entries.add(new ScoreboardEntry(player.uuid(), player.name(), player.name(),
                        0, 0, 0, 0, Math.max(0, player.ping()))));
        return new OnlineScoreboardRows(List.copyOf(entries), Set.copyOf(remaining.keySet()));
    }
}
