package org.motiquies.client;

/** Protects against reload storms, accidental startup reloads and missed resize recovery. */
public final class ShaderResizeSchedulerTest {
    public static void main(String[] args) {
        var s=new ShaderResizeScheduler();
        check(!s.take(true,640,480,0),"initial valid frame establishes baseline");
        check(!s.take(true,1280,720,100),"resize must not reload immediately");
        check(!s.take(true,1280,720,849),"wait until window is stable");
        check(s.take(true,1280,720,850),"one recovery after 750ms");
        check(!s.take(true,1280,720,5000),"completed recovery must not loop");
        s.reset();s.take(true,640,480,0);
        s.take(true,1280,720,100);s.take(true,1024,768,300);s.take(true,640,480,600);
        check(!s.take(true,640,480,1349),"dragging back to original dimensions still waits");
        check(s.take(true,640,480,1350),"coalesce the drag, including return to initial size");
        check(!s.take(true,640,480,1400),"drag produces one reload");
        s.take(true,1280,720,1500);s.take(false,1280,720,2400);
        check(!s.take(true,1280,720,5000),"unrecognized shader or disconnect cancels pending work");
        check(!s.take(true,0,0,5100),"minimized framebuffer cannot reload");
        check(!s.take(true,1280,720,6000),"restoration must stabilize even if same size");
        check(s.take(true,1280,720,6750),"recover once after minimizing/restoring");
        check(!s.take(true,1280,720,8000),"no repeated restore reload");
        System.out.println("Shader resize scheduler tests passed (startup, debounce, coalescing, cancel, minimize, no-loop)");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
