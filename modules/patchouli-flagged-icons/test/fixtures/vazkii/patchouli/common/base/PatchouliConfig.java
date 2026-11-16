package vazkii.patchouli.common.base;
public final class PatchouliConfig {
    public static boolean getConfigFlag(String flag) { return !flag.equals("mod:absent"); }
}
