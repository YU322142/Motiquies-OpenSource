package org.motiquies.client;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Connection-bound state for the optional vanilla-command download control. */
final class MapDownloadController {
    enum Command { STATUS, NEARBY, CUSTOM, DIMENSION, GLOBAL, PAUSE, RESUME }
    record Reply(String action, String mode, String previousMode, int queuePosition, int playerKiB,
                 int totalKiB, int largeSlots, String status, String notice,
                 int radius,int maxRadius,long totalBytes,long remainingBytes,long etaSeconds) {}
    record Reception(boolean hidden, boolean start, boolean stop) {}
    private static final String PREFIX = "[MotiquiesMapDownload]";
    private static final Set<String> FIELDS = Set.of("version", "action", "mode", "previousMode", "queuePosition", "playerKiB", "totalKiB", "largeSlots", "status", "notice");
    private static final Set<String> EXTRA = Set.of("radius","maxRadius","totalBytes","remainingBytes","etaSeconds");
    private static final Set<String> MODES = Set.of("NEARBY", "CUSTOM", "DIMENSION", "GLOBAL", "PAUSED");
    private static final Reception IGNORED = new Reception(false, false, false);
    private static final Reception HIDDEN = new Reception(true, false, false);
    private Object connection;
    private Command pending;
    private String expectedMode;
    private int expectedRadius;
    private long deadline;
    private boolean supported;
    private boolean allowed;
    private Reply reply;
    private String message = "进入界面后查询服务器状态。";

    void connection(Object next) {
        if (connection == next) return;
        connection = next;
        pending = null;
        expectedMode = null;
        supported = false;
        allowed = false;
        reply = null;
        message = next == null ? "请先连接服务器。" : "进入界面后查询服务器状态。";
    }

    Object connection() { return connection; }

    String begin(Object source, Command command, long now) {
        if(command==Command.CUSTOM)return null;
        return begin(source,command,now,-1);
    }
    String beginCustom(Object source,int radius,long now){
        if(!customSupported()||radius<0||radius>reply.maxRadius())return null;
        return begin(source,Command.CUSTOM,now,radius);
    }
    private String begin(Object source,Command command,long now,int radius){
        tick(now);
        if (source == null || source != connection) return null;
        if (pending != null && !(command == Command.PAUSE && supported && pending != Command.PAUSE)) return null;
        if (command != Command.STATUS && !supported) return null;
        if (command == Command.RESUME && !canResume()) return null;
        expectedMode = switch (command) {
            case NEARBY, CUSTOM, DIMENSION, GLOBAL -> command.name();
            case RESUME -> reply.previousMode();
            default -> null;
        };
        pending = command;
        expectedRadius=command==Command.CUSTOM?radius:command==Command.RESUME?reply.radius():-1;
        deadline = now + 10_000;
        if (command != Command.STATUS) allowed = false;
        message = switch (command) {
            case STATUS -> "正在查询服务器…";
            case PAUSE -> "正在暂停，已完成区域会保留。";
            default -> "正在等待服务器确认…";
        };
        return "motiquiesmap " + command.name().toLowerCase(Locale.ROOT)+(command==Command.CUSTOM?" "+radius:"");
    }

    Reception receive(Object source, boolean system, String text, long now) {
        if (!system) return IGNORED;
        Reply received = parse(text);
        if (received == null) return IGNORED;
        tick(now);
        // Valid technical messages stay out of chat even when their ACK is stale/unsolicited.
        if (source == null || source != connection) return HIDDEN;
        if (received.action().equals("status")) {
            if (!supported && pending != Command.STATUS) return HIDDEN;
            // A status already in the TCP queue may describe the pause immediately before this
            // new start/resume. Only the matching action ACK may resolve that pending choice.
            // Local downloads stay disallowed until it arrives; the server still enforces pause.
            if(received.mode().equals("PAUSED")&&pending!=null&&isStart(pending))return HIDDEN;
            boolean stop=received.mode().equals("PAUSED")&&(allowed||reply==null||!reply.mode().equals("PAUSED"));
            supported = true;
            if (pending == Command.STATUS) pending = null;
            reply = received;
            if (received.mode().equals("PAUSED")) {
                allowed = false;
                // With no pending start, server pause stops periodic downloads immediately.
                if (pending != Command.PAUSE) pending = null;
            }
            message = received.notice();
            return new Reception(true, false, stop);
        }
        if (!supported || pending == null) return HIDDEN;
        if (received.action().equals("start") && isStart(pending)
                && !received.mode().equals("PAUSED") && received.mode().equals(expectedMode)
                && (!received.mode().equals("CUSTOM")||received.radius()==expectedRadius)) {
            pending = null;
            reply = received;
            message = received.notice();
            allowed = true;
            return new Reception(true, true, false);
        }
        if (received.action().equals("pause") && pending == Command.PAUSE && received.mode().equals("PAUSED")) {
            pending = null;
            reply = received;
            message = received.notice();
            allowed = false;
            return new Reception(true, false, true);
        }
        return HIDDEN;
    }

    void tick(long now) {
        if (pending == null || now < deadline) return;
        if (pending != Command.STATUS) allowed = false;
        pending = null;
        message = supported ? "服务器尚未确认，请刷新状态后重试。" : "服务器尚不支持地图下载控制。";
    }

    void failed() {
        pending = null;
        allowed = false;
        message = "地图下载暂时无法开始，请刷新状态后重试。";
    }

    boolean supported() { return supported; }
    boolean customSupported(){return supported&&reply!=null&&reply.maxRadius()>0;}
    boolean canChoose() { return supported && pending == null; }
    boolean canQuery() { return connection != null && pending == null; }
    boolean canPause() { return supported && pending != Command.PAUSE && (reply == null || !reply.mode().equals("PAUSED") || (pending != null && isStart(pending))); }
    boolean canResume() { return supported && pending == null && reply != null && reply.mode().equals("PAUSED") && !reply.previousMode().equals("PAUSED"); }
    boolean downloadAllowed(Object source) { return source != null && source == connection && supported && allowed; }
    Reply reply() { return reply; }
    String message() { return message; }

    private static boolean isStart(Command command) {
        return command == Command.NEARBY || command == Command.CUSTOM || command == Command.DIMENSION || command == Command.GLOBAL || command == Command.RESUME;
    }

    private static Reply parse(String text) {
        if (text == null || text.length() > 2048 || !text.startsWith(PREFIX)) return null;
        try (JsonReader reader = new JsonReader(new StringReader(text.substring(PREFIX.length())))) {
            reader.setLenient(false);
            Map<String, String> fields = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ((!FIELDS.contains(name)&&!EXTRA.contains(name)) || fields.containsKey(name)) return null;
                boolean numeric = Set.of("version", "queuePosition", "playerKiB", "totalKiB", "largeSlots").contains(name)||EXTRA.contains(name);
                if (reader.peek() != (numeric ? JsonToken.NUMBER : JsonToken.STRING)) return null;
                fields.put(name, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) return null;
            int version=number(fields,"version",1,2);
            var expected=new java.util.HashSet<>(FIELDS);if(version==2)expected.addAll(EXTRA);
            if(!fields.keySet().equals(expected))return null;
            String action = fields.get("action");
            if (!Set.of("status", "start", "pause").contains(action)) return null;
            String mode = fields.get("mode"), previous = fields.get("previousMode");
            if (!MODES.contains(mode) || !MODES.contains(previous)) return null;
            if(version==1&&(mode.equals("CUSTOM")||previous.equals("CUSTOM")))return null;
            String status = plain(fields.get("status"), 64), notice = plain(fields.get("notice"), 160);
            if (status == null || notice == null) return null;
            int radius=version==2?number(fields,"radius",0,32):1;
            int max=version==2?number(fields,"maxRadius",1,32):0;
            if(version==2&&radius>max)return null;
            long total=version==2?largeNumber(fields,"totalBytes"):-1;
            long remaining=version==2?largeNumber(fields,"remainingBytes"):-1;
            long eta=version==2?largeNumber(fields,"etaSeconds"):-1;
            if(total==-1&&(remaining!=-1||eta!=-1)||total>=0&&(remaining<0||remaining>total||eta<0))return null;
            return new Reply(action, mode, previous, number(fields, "queuePosition", 0, 1_000_000),
                    number(fields, "playerKiB", 1, 1_048_576), number(fields, "totalKiB", 1, 1_048_576),
                    number(fields, "largeSlots", 1, 1024), status, notice,radius,max,total,remaining,eta);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static int number(Map<String, String> fields, String name, int min, int max) {
        String text = fields.get(name);
        if (!text.matches("0|[1-9][0-9]{0,9}")) throw new IllegalArgumentException("integer required");
        int value = Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException("integer out of range");
        return value;
    }
    private static long largeNumber(Map<String,String> fields,String name){
        String text=fields.get(name);
        if(!text.matches("-1|0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("integer required");
        long value=Long.parseLong(text);
        if(value>(1L<<60))throw new IllegalArgumentException("estimate too large");
        return value;
    }

    private static String plain(String value, int max) {
        if (value.length() > max || value.chars().anyMatch(c -> Character.isISOControl(c) || c == 0xA7)) return null;
        return value;
    }
}
