package com.tom.storagemod.menu;

import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;

import com.tom.storagemod.inventory.StoredItemStack;

class TerminalRecipePlacer extends ServerPlaceRecipe<CraftingInput, CraftingRecipe> {
	private CraftingTerminalMenu menu;
	private Player player;

	public TerminalRecipePlacer(CraftingTerminalMenu p_135431_, Player player) {
		super(p_135431_);
		this.menu = p_135431_;
		this.player = player;
	}

	@Override
	protected int moveItemToGrid(Slot slotToFill, ItemStack ingredientIn, int count) {
		int inInv = this.inventory.findSlotMatchingUnusedItem(ingredientIn);
		if (inInv != -1) {
			final ItemStack itemStack2 = this.inventory.getItem(inInv);
			int k;
			if (count < itemStack2.getCount()) {
				this.inventory.removeItem(inInv, count);
				k = count;
			} else {
				this.inventory.removeItemNoUpdate(inInv);
				k = itemStack2.getCount();
			}
			if (slotToFill.getItem().isEmpty()) {
				slotToFill.set(itemStack2.copyWithCount(k));
			} else {
				slotToFill.getItem().grow(k);
			}
			return count - k;
		} else if(this.menu != null) {
			StoredItemStack st = this.menu.pullStack(new StoredItemStack(ingredientIn), 1);
			if(st != null) {
				if (slotToFill.getItem().isEmpty()) {
					slotToFill.set(st.getActualStack());
				} else {
					slotToFill.getItem().grow(1);
				}
				return -1;
			}
		}
		return -1;
	}

	@Override
	protected void clearGrid() {
		this.menu.clearCraftingForPlayer(player);
	}
}
