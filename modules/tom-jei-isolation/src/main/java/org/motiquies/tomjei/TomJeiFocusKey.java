package org.motiquies.tomjei;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.input.IInternalKeyMappings;
import net.minecraft.client.gui.components.EditBox;

/** The pinned JEI public API has no focus-search getter; its public internal interface does. */
public final class TomJeiFocusKey {
    private TomJeiFocusKey() {}

    public static boolean shouldYield(EditBox box,int key,int scan,IJeiRuntime runtime) {
        if (runtime == null || !box.isActive() || !box.isFocused() || !box.canConsumeInput()) return false;
        if (!runtime.getIngredientListOverlay().isListDisplayed()) return false;
        if (!(runtime.getKeyMappings() instanceof IInternalKeyMappings mappings)) return false;
        var focus = mappings.getFocusSearch();
        return !focus.isUnbound() && focus.isActiveAndMatches(InputConstants.getKey(key,scan));
    }
}
