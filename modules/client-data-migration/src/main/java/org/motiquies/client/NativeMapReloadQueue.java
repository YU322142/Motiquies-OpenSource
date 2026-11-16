package org.motiquies.client;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Main-thread dirty queue plus a bounded native-thread disk reload operation. */
final class NativeMapReloadQueue {
    record Key(int x, int z, int caveLayer) {}
    record Context(Object connection, Object processor, Object dimension, String multiworld, int generation) {
        boolean sameSession(Context other) {
            return other != null && connection == other.connection && processor == other.processor;
        }
        boolean sameMap(Context other) {
            return sameSession(other) && dimension == other.dimension && Objects.equals(multiworld, other.multiworld);
        }
    }
    private record Target(Object dimension, String multiworld, Key key) {
        boolean belongsTo(Context context) {
            return dimension == context.dimension() && Objects.equals(multiworld, context.multiworld());
        }
    }
    private record Pending(long revision, Object prepared) {}
    record Ticket(Context context, Key key, long revision, Object prepared) {}
    enum Handoff { DONE, WAIT, REPREPARE }
    interface Access { void pauseRender(); void resumeRender(); boolean mayLoadAnother(); Region find(Key key); }
    interface Region {
        Object lock(); void markTerrain(); boolean canReload(); byte loadState();
        void pauseWriter(); void resumeWriter();
        boolean invalidateCache(); void clearLoadedData(); boolean loadStarted(); void load(); void prioritize();
    }
    private final LinkedHashMap<Target, Pending> pending = new LinkedHashMap<>();
    private Context context;
    private long revision;
    void enqueue(Context next, Key key) {
        updateContext(next);
        if (next != null) pending.put(new Target(next.dimension(), next.multiworld(), key), new Pending(++revision, null));
    }
    void enqueuePrepared(Context next, Key key, Object prepared) {
        enqueue(next, key);
        if (next != null) {
            Target target = new Target(next.dimension(), next.multiworld(), key);
            pending.put(target, new Pending(revision, prepared));
        }
    }
    Ticket next(Context next) {
        updateContext(next);
        if (next == null) return null;
        Target target = pending.keySet().stream().filter(item -> item.belongsTo(next)).findFirst().orElse(null);
        Pending work = target == null ? null : pending.get(target);
        return work == null ? null : new Ticket(next, target.key(), work.revision(), work.prepared());
    }
    void prepared(Ticket ticket, Object identity) {
        if (context == null || !context.sameSession(ticket.context())) return;
        Target target = new Target(ticket.context().dimension(), ticket.context().multiworld(), ticket.key());
        Pending current = pending.get(target);
        if (current != null && current.revision() == ticket.revision()) {
            pending.put(target, new Pending(current.revision(), identity));
        }
    }
    void complete(Ticket ticket, boolean submitted) {
        if (context == null || !context.sameSession(ticket.context())) return;
        Target target = new Target(ticket.context().dimension(), ticket.context().multiworld(), ticket.key());
        Pending current = pending.get(target);
        if (current == null || current.revision() != ticket.revision()) return;
        pending.remove(target);
        if (!submitted) pending.put(target, current);
    }
    void completeHandoff(Ticket ticket, Handoff handoff) {
        if (handoff == Handoff.DONE) complete(ticket, true);
        else if (handoff == Handoff.REPREPARE) prepared(ticket, null);
        else complete(ticket, false);
    }

    /** Called once at the native MapRunner task boundary, after its IO/cache/refresh cycle. */
    static Object prepare(Ticket ticket, Access access) {
        access.pauseRender();
        try {
            if (!access.mayLoadAnother()) return null;
            Region region = access.find(ticket.key());
            if (region == null) return null;
            region.pauseWriter();
            try {
                synchronized (region.lock()) {
                    if (!region.canReload() || !region.invalidateCache()) return null;
                    if (region.loadState() == 2) region.clearLoadedData();
                    region.markTerrain();
                    return region.lock();
                }
            } finally {
                region.resumeWriter();
            }
        } finally {
            access.resumeRender();
        }
    }
    /** Main thread only: requestLoad also prepares highlights and has a native thread guard. */
    static Handoff handoff(Ticket ticket, Access access) {
        access.pauseRender();
        try {
            Region region = access.find(ticket.key());
            if (region == null) return Handoff.WAIT;
            if (ticket.prepared() == null || ticket.prepared() != region.lock()) return Handoff.REPREPARE;
            // Native FullReloader's main path uses renderSync -> region monitor. Acquiring
            // writer pause here would introduce a different lock order from native code.
            synchronized (region.lock()) {
                if (region.loadStarted()) return Handoff.DONE;
                if (!access.mayLoadAnother() || !region.canReload()) return Handoff.WAIT;
                region.load();
                region.prioritize();
                return Handoff.DONE;
            }
        } finally { access.resumeRender(); }
    }
    int size() { return pending.size(); }
    void clear() { pending.clear(); context = null; }

    void updateContext(Context next) {
        // These notifications represent completed disk writes, not queued network work.
        // Keep them across sync generations and temporary dimension/multiworld switches.
        // A new native session reloads disk itself; its old region identities are discarded.
        if (context == null || !context.sameSession(next)) pending.clear();
        context = next;
    }
}
