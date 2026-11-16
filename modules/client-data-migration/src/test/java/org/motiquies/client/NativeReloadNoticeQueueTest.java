package org.motiquies.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeReloadNoticeQueueTest {
    public static void main(String[] args) {
        var queue = new NativeReloadNoticeQueue(); Object connection = new Object(), processor = new Object();
        Object overworld = new Object(), nether = new Object(); var key = new NativeMapReloadQueue.Key(0, 0, Integer.MAX_VALUE);
        Path source = Path.of("C:/qa/null/mw$1"); List<NativeMapReloadQueue.Key> loaded = new ArrayList<>();
        queue.enqueue(connection, processor, overworld, source, key);
        queue.resolve(null, null, null, loaded::add);
        check(queue.size() == 1 && loaded.isEmpty(), "native context gap must retain the completed-source notice");
        var context = new NativeMapReloadQueue.Context(connection, processor, new Object(), "mw$1", 7);
        queue.resolve(context, nether, source, loaded::add);
        check(queue.size() == 1 && loaded.isEmpty(), "old native dimension cannot consume a new Minecraft dimension notice");
        queue.resolve(context, overworld, Path.of("C:/qa/null/mw$2"), loaded::add);
        check(queue.size() == 1 && loaded.isEmpty(), "different multiworld source must not consume a notice");
        queue.resolve(context, overworld, source, loaded::add);
        check(queue.size() == 0 && loaded.size() == 1, "exact owning native map resolves the retained notice once");
        queue.enqueue(connection, processor, overworld, null, key);
        queue.resolve(context, overworld, source, loaded::add);
        check(loaded.size() == 2, "original deferred callback without lastMwDir still retains its proven dimension");
        queue.enqueue(connection, processor, overworld, source, key);
        queue.resolve(new NativeMapReloadQueue.Context(new Object(), new Object(), new Object(), "mw$1", 7), overworld, source, loaded::add);
        check(queue.size() == 0 && loaded.size() == 2, "a new native session discards old notice identities");
        System.out.println("Native reload notice transition tests passed");
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
