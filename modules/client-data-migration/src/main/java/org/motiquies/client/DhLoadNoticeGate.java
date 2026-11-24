package org.motiquies.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** Coalesces the duplicate DH client-level chat notice without changing DH's load or logging path. */
public final class DhLoadNoticeGate {
    private static final int MAX_TRACKED_LEVELS = 32;
    private static final long QUIET_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Pattern FORMATTING = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOAD_FAILURE = Pattern.compile(
            "Distant Horizons: Client level loading failed\\.\nUnable to load level \\[([^\\]\\r\\n]{1,160})\\], "
                    + "LODs may not appear\\. See log for more information\\.[ \\t]*\n?");
    private static final DhLoadNoticeGate SHARED = new DhLoadNoticeGate();

    private final LinkedHashMap<String, Long> displayed = new LinkedHashMap<>();

    public static boolean shouldEnqueue(String message) {
        return SHARED.allows(message, System.nanoTime());
    }

    public static void reset() {
        SHARED.clear();
    }

    synchronized boolean allows(String message, long now) {
        if (!message.contains("Distant Horizons: Client level loading failed.")) return true;
        var notice = LOAD_FAILURE.matcher(FORMATTING.matcher(message).replaceAll(""));
        if (!notice.matches()) return true;
        String level = notice.group(1);
        Long shownAt = displayed.get(level);
        if (shownAt != null && now - shownAt < QUIET_NANOS) return false;
        displayed.remove(level);
        displayed.put(level, now);
        if (displayed.size() > MAX_TRACKED_LEVELS) displayed.pollFirstEntry();
        return true;
    }

    synchronized void clear() {
        displayed.clear();
    }
}
