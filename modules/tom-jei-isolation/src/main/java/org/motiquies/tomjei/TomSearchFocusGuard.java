package org.motiquies.tomjei;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.components.EditBox;

public final class TomSearchFocusGuard {
    private TomSearchFocusGuard() {}

    /** Preserve Tom's auto-focus unless the JEI overlay currently owns keyboard input. */
    public static void apply(EditBox box, boolean focused, IJeiRuntime runtime) {
        boolean jeiOwnsInput = runtime != null
                && runtime.getIngredientListOverlay().hasKeyboardFocus();
        box.setFocused(focused && !jeiOwnsInput);
    }
}
