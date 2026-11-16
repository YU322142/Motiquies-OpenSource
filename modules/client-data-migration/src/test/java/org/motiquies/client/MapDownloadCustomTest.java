package org.motiquies.client;
public final class MapDownloadCustomTest {
    static final String STATUS="[MotiquiesMapDownload]{\"version\":2,\"action\":\"status\",\"mode\":\"CUSTOM\",\"previousMode\":\"CUSTOM\",\"queuePosition\":0,\"playerKiB\":256,\"totalKiB\":512,\"largeSlots\":1,\"status\":\"下载中\",\"notice\":\"按已完成区域续传\",\"radius\":4,\"maxRadius\":32,\"totalBytes\":1048576,\"remainingBytes\":524288,\"etaSeconds\":2}";
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static MapDownloadController ready(){var c=new MapDownloadController();Object o=new Object();c.connection(o);c.begin(o,MapDownloadController.Command.STATUS,0);c.receive(o,true,STATUS,1);return c;}
    public static void main(String[] args){
        var c=ready();Object o=c.connection();check(c.customSupported(),"v2 negotiation");
        check(c.reply().remainingBytes()==524288&&c.reply().etaSeconds()==2,"real byte/ETA values");
        check("motiquiesmap custom 4".equals(c.beginCustom(o,4,2)),"bounded command");
        check(!c.receive(o,true,STATUS.replace("\"action\":\"status\"","\"action\":\"start\"").replace("\"radius\":4","\"radius\":5"),3).start(),"wrong radius cannot authorize");
        check(c.receive(o,true,STATUS.replace("\"action\":\"status\"","\"action\":\"start\""),4).start(),"matching radius starts once");
        for(int n:new int[]{-1,33,Integer.MAX_VALUE}){var x=ready();check(x.beginCustom(x.connection(),n,2)==null,"invalid custom blocked");}
        for(String bad:new String[]{STATUS.replace("\"remainingBytes\":524288","\"remainingBytes\":1048577"),STATUS.replace("\"etaSeconds\":2","\"etaSeconds\":-2"),STATUS.replace("\"radius\":4","\"radius\":33"),STATUS.replace("\"totalBytes\":1048576","\"totalBytes\":\"1048576\""),STATUS.replace("\"version\":2","\"version\":2,\"radius\":3")}){
            var x=new MapDownloadController();Object conn=new Object();x.connection(conn);x.begin(conn,MapDownloadController.Command.STATUS,0);
            check(!x.receive(conn,true,bad,1).hidden()&&!x.supported(),"malformed extended ACK ignored");
        }
        c=ready();o=c.connection();c.begin(o,MapDownloadController.Command.PAUSE,2);
        c.receive(o,true,STATUS.replace("\"action\":\"status\"","\"action\":\"pause\"").replace("\"mode\":\"CUSTOM\"","\"mode\":\"PAUSED\""),3);
        check(c.canResume(),"paused custom resumable");c.begin(o,MapDownloadController.Command.RESUME,4);
        check(c.receive(o,true,STATUS.replace("\"action\":\"status\"","\"action\":\"start\""),5).start(),"resume retains radius");
        c=ready();o=c.connection();c.begin(o,MapDownloadController.Command.PAUSE,2);
        String pausedStatus=STATUS.replace("\"mode\":\"CUSTOM\"","\"mode\":\"PAUSED\"");
        c.receive(o,true,pausedStatus.replace("\"action\":\"status\"","\"action\":\"pause\""),3);
        c.begin(o,MapDownloadController.Command.RESUME,4);
        c.receive(o,true,pausedStatus,5); // already queued before the resume request reached the server
        check(!c.downloadAllowed(o),"stale status cannot authorize start");
        check(c.receive(o,true,STATUS.replace("\"action\":\"status\"","\"action\":\"start\""),6).start(),"queued pause status must not consume new matching resume ACK");
        check(DownloadDisplay.duration(3661).equals("约 1小时2分"),"round upward, readable time");
        check(DownloadDisplay.duration(-1).contains("统计"),"unknown is not zero");
        check(DownloadDisplay.bytes(1048576).equals("1.0 MiB"),"binary units");
        System.out.println("PASS custom controller: v2, scope ACK binding, bounds, strict parsing, pause/resume, byte/time display");
    }
}
