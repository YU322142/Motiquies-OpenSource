package org.motiquies.client.tab.mixin;

import org.mods.gd656killicon.client.gui.elements.GDTextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GDTextRenderer.class, remap = false)
public interface Gd656TextRendererAccessor {
    @Accessor("fontSize")
    void motiquies$setFontSize(float fontSize);
}
