package org.motiquies.client;

import java.lang.reflect.Method;

public final class AndroidPonderGateTest {
    public static void main(String[] args) throws Exception {
        Class<?> plugin;
        try {
            plugin = Class.forName("org.motiquies.client.AndroidPonderMixinPlugin");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Android Ponder mixin has no platform gate", missing);
        }
        Method gate = plugin.getDeclaredMethod("shouldSkipStencil", String.class, String.class, String.class);
        gate.setAccessible(true);
        String androidApp = "/data/app/launcher-qa/lib/arm64";
        String androidUser = "/data/user/0/launcher-qa/lib";
        String krypton = "libng_gl4es.so";

        check(gate, true, androidApp, krypton, null);
        check(gate, true, androidUser, androidApp + "/" + krypton, null);
        check(gate, false, androidApp, "libMobileGlues.so", null);
        check(gate, false, androidApp, null, null);
        check(gate, false, null, krypton, null);
        check(gate, false, "C:\\Zalith\\lib", krypton, null);
        check(gate, false, androidApp, "opengl32.dll", null);
        check(gate, false, androidApp, "different-" + krypton, null);
        check(gate, false, androidApp, krypton, "false");
        check(gate, false, null, krypton, "true");
        try {
            Class.forName("org.motiquies.client.android.mixin.AndroidTaczStencilMixin");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Android TaCZ framebuffer initialization has no scoped workaround", missing);
        }
        System.out.println("Android Ponder gate: 10 cases passed");
    }

    private static void check(Method gate, boolean expected, String natives, String renderer, String enabled)
            throws Exception {
        boolean actual = (boolean) gate.invoke(null, natives, renderer, enabled);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " for " + natives + ", " + renderer
                    + ", " + enabled + " but got " + actual);
        }
    }
}
