package org.motiquies.client;

/** A confirmed manual request has first claim on the next native-idle client tick. */
final class MapDownloadStartScheduler {
    private Object pendingStart;
    void arm(Object connection) { pendingStart = connection; }
    void cancel() { pendingStart = null; }
    boolean pending() { return pendingStart != null; }
    boolean take(Object connection, boolean permitted, boolean hashing, boolean writing, boolean syncing) {
        if (pendingStart == null) return false;
        if (connection == null || connection != pendingStart || !permitted) {
            cancel();
            return false;
        }
        if (hashing || writing || syncing) return false;
        pendingStart = null;
        return true;
    }
    boolean canRunPeriodic(Object connection, boolean permitted, boolean hashing, boolean writing, boolean syncing) {
        return !pending() && connection != null && permitted && !hashing && !writing && !syncing;
    }
}
