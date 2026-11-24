package org.motiquies.client;

import java.time.Duration;

public final class DhLoadNoticeGateTest {
    public static void main(String[] args) {
        DhLoadNoticeGate gate = new DhLoadNoticeGate();
        long window = Duration.ofMinutes(1).toNanos();
        String overworld = warning("fixture@minecraft:overworld");
        String nether = warning("fixture@minecraft:the_nether");
        require(gate.allows(overworld, 0L), "The first warning must appear");
        for (int i = 1; i <= 100; i++) {
            require(!gate.allows(overworld, i), "Repeated warning must be coalesced");
        }
        require(gate.allows(nether, 120L), "Different failed levels should have independent notices");
        require(gate.allows("DH network warning", 130L), "Other DH notifications must pass");
        require(gate.allows("Distant Horizons: Client level loading failed. Other notification", 140L),
                "The filter must only match the complete native warning");
        require(gate.allows(overworld, window), "One re-notification should be possible after a minute");
        require(!gate.allows(overworld, window + 1), "The re-notification must start a new quiet window");
        gate.clear();
        require(gate.allows(overworld, window + 2), "A new connection must display its first warning");
        System.out.println("DH duplicate warning, other notices, level independence and reconnect passed");
    }

    static String warning(String level) {
        return "§cDistant Horizons: Client level loading failed.§r\nUnable to load level ["
                + "§e" + level + "§r], LODs may not appear. See log for more information. \n";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
