package org.motiquies.client;

/** Coalesces a resize gesture into one delayed recovery, without game dependencies. */
final class ShaderResizeScheduler {
    private int width,height;
    private long changedAt;
    private boolean pending,invalid;

    boolean take(boolean eligible,int nextWidth,int nextHeight,long now) {
        if(!eligible){reset();return false;}
        if(nextWidth<=0||nextHeight<=0){invalid=width>0;return false;}
        if(width==0){width=nextWidth;height=nextHeight;invalid=false;return false;}
        if(invalid||width!=nextWidth||height!=nextHeight){
            width=nextWidth;height=nextHeight;changedAt=now;pending=true;invalid=false;
        }
        if(pending&&now-changedAt>=750){pending=false;return true;}
        return false;
    }
    void reset(){width=height=0;pending=invalid=false;changedAt=0;}
}
