package org.motiquies.client;
import java.util.Locale;
final class DownloadDisplay {
    static String bytes(long bytes){
        if(bytes<0)return "统计中";
        if(bytes<1024)return bytes+" B";
        String[] units={"KiB","MiB","GiB","TiB","PiB"};double n=bytes/1024.0;int i=0;
        while(n>=1024&&i<units.length-1){n/=1024;i++;}
        return String.format(Locale.ROOT,"%.1f %s",n,units[i]);
    }
    static String duration(long seconds){
        if(seconds<0)return "统计中";
        if(seconds==0)return "等待客户端确认完成";
        if(seconds<60)return "约 "+seconds+"秒";
        long minutes=(seconds+59)/60;
        if(minutes<60)return "约 "+minutes+"分";
        long hours=minutes/60;minutes%=60;
        return "约 "+hours+"小时"+(minutes==0?"":minutes+"分");
    }
    private DownloadDisplay(){}
}
