package org.motiquies.patchoulijeikeys;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/** Resolves the current native mappings only when the book actually handles a key. */
public final class JeiBookKeys {
    private JeiBookKeys() {}
    public static boolean handle(IJeiRuntime runtime,int keyCode,int scanCode,ItemStack hovered) {
        if(runtime==null)return false;
        var key=InputConstants.getKey(keyCode,scanCode);
        var mappings=runtime.getKeyMappings();
        RecipeIngredientRole role;
        if(mappings.getShowRecipe().isActiveAndMatches(key))role=RecipeIngredientRole.OUTPUT;
        else if(mappings.getShowUses().isActiveAndMatches(key))role=RecipeIngredientRole.INPUT;
        else return false;
        var focus=runtime.getJeiHelpers().getFocusFactory().createFocus(role,VanillaTypes.ITEM_STACK,hovered);
        runtime.getRecipesGui().show(focus);
        return true;
    }
}
