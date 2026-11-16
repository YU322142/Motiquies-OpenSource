package org.motiquies.client;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;

/** Main-thread notices retain proven dimension/source identity through native setup gaps. */
final class NativeReloadNoticeQueue {
    private record Notice(Object connection, Object processor, Object dimensionId, Path directory, NativeMapReloadQueue.Key key) {}
    private final LinkedHashSet<Notice> pending = new LinkedHashSet<>();
    void enqueue(Object connection, Object processor, Object dimensionId, Path directory, NativeMapReloadQueue.Key key) {
        if (connection == null || (dimensionId == null && directory == null)) return;
        pending.add(new Notice(connection, processor, dimensionId, normalized(directory), key));
    }
    void resolve(NativeMapReloadQueue.Context context, Object dimensionId, Path directory, Consumer<NativeMapReloadQueue.Key> ready) {
        if (context == null) return;
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Notice notice = iterator.next();
            if (notice.connection() != context.connection() || (notice.processor() != null && notice.processor() != context.processor())) {
                iterator.remove(); continue;
            }
            if (notice.dimensionId() != null && !Objects.equals(notice.dimensionId(), dimensionId)) continue;
            if (notice.directory() != null && !notice.directory().equals(normalized(directory))) continue;
            ready.accept(notice.key());
            iterator.remove();
        }
    }
    int size() { return pending.size(); }
    void clear() { pending.clear(); }
    private static Path normalized(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
}
