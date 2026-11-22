package org.motiquies.client;

import java.util.ArrayList;
import java.util.List;

/** No Minecraft runtime: tests the real protocol/state boundary, not a transport fake. */
public final class MapDownloadControllerTest {
    private static final String PREFIX = "[MotiquiesMapDownload]";
    private static final String STATUS = PREFIX + "{\"version\":1,\"action\":\"status\",\"mode\":\"NEARBY\",\"previousMode\":\"GLOBAL\",\"queuePosition\":0,\"playerKiB\":256,\"totalKiB\":512,\"largeSlots\":1,\"status\":\"待命\",\"notice\":\"按已完成区域续传\"}";

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        run(failures, "query cannot start a download", MapDownloadControllerTest::queryOnly);
        run(failures, "start requires matching same-connection ACK", MapDownloadControllerTest::startGate);
        run(failures, "reject spoofed and malformed protocol", MapDownloadControllerTest::validation);
        run(failures, "subscription status preserves pending start", MapDownloadControllerTest::queueUpdates);
        run(failures, "pause and resume preserve the chosen range", MapDownloadControllerTest::pauseResume);
        run(failures, "pause preempts pending refresh and start", MapDownloadControllerTest::pausePriority);
        run(failures, "connection and timeout invalidate pending starts", MapDownloadControllerTest::connectionAndTimeout);
        run(failures, "paused heartbeat stops native work only on transition", MapDownloadControllerTest::pausedHeartbeat);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Map download controller tests passed (8 scenarios)");
    }

    private static void queryOnly() {
        var c = new MapDownloadController(); Object connection = new Object(); c.connection(connection);
        check(c.begin(connection, MapDownloadController.Command.GLOBAL, 0) == null, "unsupported server cannot receive a selection");
        check("motiquiesmap status".equals(c.begin(connection, MapDownloadController.Command.STATUS, 0)), "opening only requests status");
        var received = c.receive(connection, true, STATUS, 1);
        check(received.hidden() && !received.start(), "status is hidden and never starts MapSyncer");
        check(c.supported() && c.canChoose(), "a valid status confirms controls");
        check(c.reply().mode().equals("NEARBY") && !c.downloadAllowed(connection), "default range is nearby but querying does not authorize auto-download");
        check(c.backgroundRefreshAllowed(connection), "nearby notice can use the confirmed status for this connection");
        check(!c.backgroundRefreshAllowed(new Object()), "nearby status is tied to the current connection");
    }

    private static void startGate() {
        var c = ready(); Object connection = c.connection();
        check(!c.receive(connection, true, start("GLOBAL"), 2).start(), "unsolicited start ignored");
        check("motiquiesmap global".equals(c.begin(connection, MapDownloadController.Command.GLOBAL, 3)), "global command");
        check(!c.receive(new Object(), true, start("GLOBAL"), 4).start(), "different connection cannot ACK");
        check(!c.receive(connection, false, start("GLOBAL"), 4).hidden(), "player message never hidden or accepted");
        check(!c.receive(connection, true, start("DIMENSION"), 4).start(), "wrong range cannot ACK");
        var received = c.receive(connection, true, start("GLOBAL"), 5);
        check(received.hidden() && received.start() && c.downloadAllowed(connection), "only matching ACK authorizes start");
        check(!c.receive(connection, true, start("GLOBAL"), 6).start(), "duplicate ACK cannot restart");
    }

    private static void validation() {
        String[] invalid = {
                STATUS.replace("\"version\":1", "\"version\":2"),
                STATUS.replace("\"version\":1", "\"version\":\"1\""),
                STATUS.replace("\"version\":1", "\"version\":1,\"version\":1"),
                STATUS.replace("\"mode\":\"NEARBY\"", "\"mode\":\"UNLIMITED\""),
                STATUS.replace("\"previousMode\":\"GLOBAL\",", ""),
                STATUS.replace("\"queuePosition\":0", "\"queuePosition\":-1"),
                STATUS.replace("\"queuePosition\":0", "\"queuePosition\":0.5"),
                STATUS.replace("\"playerKiB\":256", "\"playerKiB\":0"),
                STATUS.replace("\"largeSlots\":1", "\"largeSlots\":1000000"),
                STATUS.replace("\"status\":\"待命\"", "\"status\":null"),
                STATUS.replace("待命", "x".repeat(65)),
                STATUS.replace("按已完成区域续传", "x".repeat(161)),
                STATUS.replace("待命", "待\\n命"),
                STATUS.replace("\"version\":1", "\"extra\":true,\"version\":1"),
                STATUS + "{}", STATUS.replace("{\"version\"", "{version"),
                " " + STATUS, "<player> " + STATUS, PREFIX + "[1]", PREFIX + "x".repeat(2049)
        };
        for (String input : invalid) {
            var c = new MapDownloadController(); Object connection = new Object(); c.connection(connection);
            c.begin(connection, MapDownloadController.Command.STATUS, 0);
            var received = c.receive(connection, true, input, 1);
            check(!received.hidden() && !c.supported(), "invalid message must not be accepted: " + input.substring(0, Math.min(input.length(), 100)));
        }
    }

    private static void queueUpdates() {
        var c = ready(); Object connection = c.connection();
        c.begin(connection, MapDownloadController.Command.GLOBAL, 2);
        var update = c.receive(connection, true, status("GLOBAL").replace("\"queuePosition\":0", "\"queuePosition\":2"), 3);
        check(update.hidden() && !update.start() && c.reply().queuePosition() == 2, "subscribed queue status displayed without starting");
        check(c.receive(connection, true, start("GLOBAL"), 4).start(), "queue status does not consume the pending start ACK");
        c.receive(connection, true, status("GLOBAL").replace("待命", "下载中"), 5);
        check(c.reply().status().equals("下载中"), "asynchronous status remains accepted after request finishes");
    }

    private static void pauseResume() {
        var c = ready(); Object connection = c.connection();
        c.begin(connection, MapDownloadController.Command.DIMENSION, 2);
        c.receive(connection, true, start("DIMENSION"), 3);
        check("motiquiesmap pause".equals(c.begin(connection, MapDownloadController.Command.PAUSE, 4)), "pause command");
        check(!c.downloadAllowed(connection), "pause immediately prevents periodic restart while waiting for server");
        String paused = STATUS.replace("\"action\":\"status\"", "\"action\":\"pause\"").replace("\"mode\":\"NEARBY\"", "\"mode\":\"PAUSED\"").replace("\"previousMode\":\"GLOBAL\"", "\"previousMode\":\"DIMENSION\"");
        check(c.receive(connection, true, paused, 5).stop() && c.canResume(), "pause ACK enables resume");
        check("motiquiesmap resume".equals(c.begin(connection, MapDownloadController.Command.RESUME, 6)), "resume command");
        check(!c.receive(connection, true, start("GLOBAL"), 7).start(), "resume cannot silently expand the range");
        check(c.receive(connection, true, start("DIMENSION"), 8).start(), "resume waits for ACK and restores the previous range");
        c.receive(connection, true, status("PAUSED"), 9);
        check(!c.downloadAllowed(connection), "server-side paused status also blocks periodic restart");
    }

    private static void connectionAndTimeout() {
        var c = ready(); Object old = c.connection();
        c.begin(old, MapDownloadController.Command.GLOBAL, 2);
        Object next = new Object(); c.connection(next);
        check(!c.supported() && !c.receive(old, true, start("GLOBAL"), 3).start(), "reconnect drops support and outstanding ACK");
        c.begin(next, MapDownloadController.Command.STATUS, 4); c.tick(10_005);
        check(!c.supported() && !c.canChoose() && c.message().contains("尚不支持"), "old server times out with unsupported message");
        check(!c.receive(next, true, STATUS, 10_006).start() && !c.supported(), "late initial response cannot authorize controls");
        c = ready(); Object connection = c.connection();
        c.begin(connection, MapDownloadController.Command.GLOBAL, 2); c.tick(10_003);
        check(!c.receive(connection, true, start("GLOBAL"), 10_004).start(), "late start ACK ignored");
        check(!c.downloadAllowed(connection), "timeout does not enable periodic download");
    }

    private static void pausePriority() {
        var c = ready(); Object connection = c.connection();
        c.begin(connection, MapDownloadController.Command.STATUS, 2);
        check(c.canPause(), "an outstanding refresh must not block pause");
        check("motiquiesmap pause".equals(c.begin(connection, MapDownloadController.Command.PAUSE, 3)), "pause preempts refresh");
        check(!c.receive(connection, true, start("NEARBY"), 4).start(), "no start ACK can override pending pause");
        c.receive(connection, true, STATUS, 5);
        check(!c.canChoose(), "late status does not consume pause request");
        c = ready(); connection = c.connection();
        c.begin(connection, MapDownloadController.Command.GLOBAL, 2);
        check("motiquiesmap pause".equals(c.begin(connection, MapDownloadController.Command.PAUSE, 3)), "pause preempts start");
        check(!c.receive(connection, true, start("GLOBAL"), 4).start(), "preempted start ACK remains inert");
    }

    private static MapDownloadController ready() {
        var c = new MapDownloadController(); Object connection = new Object(); c.connection(connection);
        c.begin(connection, MapDownloadController.Command.STATUS, 0); c.receive(connection, true, STATUS, 1); return c;
    }
    private static void pausedHeartbeat(){
        var c=ready();Object connection=c.connection();
        check(c.receive(connection,true,status("PAUSED"),2).stop(),"first server pause stops native work");
        check(!c.receive(connection,true,status("PAUSED"),3).stop(),"unchanged heartbeat must not cancel/announce once per second");
        check(!c.downloadAllowed(connection),"remains paused");
        check(!c.backgroundRefreshAllowed(connection),"pause blocks nearby notices");
    }
    private static String status(String mode) { return STATUS.replace("\"mode\":\"NEARBY\"", "\"mode\":\"" + mode + "\""); }
    private static String start(String mode) { return status(mode).replace("\"action\":\"status\"", "\"action\":\"start\""); }
    private static void run(List<String> failures, String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError e) { failures.add(name + ": " + e.getMessage()); System.out.println("FAIL " + name + ": " + e.getMessage()); }
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
