package org.motiquies.patchouliicons;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import vazkii.patchouli.common.base.PatchouliConfig;

/** Applies BookEntry.canAdd's existing flag rule before its resource parser runs. */
public final class FlaggedBookIcons {
    private FlaggedBookIcons() {}

    public static boolean canLoad(JsonObject json) {
        String flag = GsonHelper.getAsString(json, "flag", "");
        return flag.isEmpty() || PatchouliConfig.getConfigFlag(flag);
    }
}
