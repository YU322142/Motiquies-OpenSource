package com.tom.storagemod.menu;

import java.util.List;
import java.util.Optional;
import java.lang.ref.WeakReference;

import net.minecraft.client.RecipeBookCategories;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import com.google.common.collect.Lists;

import com.tom.storagemod.Content;
import com.tom.storagemod.StorageMod;
import com.tom.storagemod.block.entity.CraftingTerminalBlockEntity;
import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.inventory.TerminalItemStack;
import com.tom.storagemod.platform.NeoForgeMenu;
import com.tom.storagemod.polymorph.PolymorphHelper;
import com.tom.storagemod.util.IAutoFillTerminal;
import com.tom.storagemod.util.IDataReceiver;
import com.tom.storagemod.util.CraftingMatrix;

public class CraftingTerminalMenu extends StorageTerminalMenu implements IAutoFillTerminal, IDataReceiver, NeoForgeMenu {
	public static class SlotCrafting extends Slot {
		public SlotCrafting(Container inventoryIn, int index, int xPosition, int yPosition) {
			super(inventoryIn, index, xPosition, yPosition);
		}
	}

	private final CraftingContainer craftMatrix;
	private final ResultContainer craftResult;
	private Slot craftingResultSlot;
	private final List<ContainerListener> listeners = Lists.newArrayList();
	private long lastRecipeFillGameTime = Long.MIN_VALUE;
	private Optional<RecipeHolder<CraftingRecipe>> currentRecipe = Optional.empty();
	private boolean refillingGrid;
	private int craftingCooldown;
	private long craftingCooldownGameTime = Long.MIN_VALUE;
	private WeakReference<Player> polymorphPlayer;

	@Override
	public void addSlotListener(ContainerListener listener) {
		super.addSlotListener(listener);
		listeners.add(listener);
	}

	@Override
	public void removeSlotListener(ContainerListener listener) {
		super.removeSlotListener(listener);
		listeners.remove(listener);
	}

	public CraftingTerminalMenu(int id, Inventory inv, CraftingTerminalBlockEntity te) {
		super(Content.craftingTerminalMenu.get(), id, inv, te);
		// The storage network remains shared through the block entity, while the
		// crafting grid/result are owned by this menu (one player session).
		craftMatrix = new CraftingMatrix(3, 3, this::onCraftingMatrixChangedInternal);
		craftResult = new ResultContainer();
		init();
		this.addPlayerSlots(inv, 8, 174);
		onCraftingMatrixChangedInternal();
	}

	public CraftingTerminalMenu(int id, Inventory inv) {
		super(Content.craftingTerminalMenu.get(), id, inv);
		craftMatrix = new TransientCraftingContainer(this, 3, 3);
		craftResult = new ResultContainer();
		init();
		this.addPlayerSlots(inv, 8, 174);
	}

	@Override
	public void removed(Player playerIn) {
		super.removed(playerIn);
		if(te != null && !playerIn.level().isClientSide) {
			clearCraftingForPlayer(playerIn);
			((CraftingTerminalBlockEntity) te).unregisterMenu(this);
		}
	}

	private void init() {
		int x = -4;
		int y = 94;
		this.addSlot(craftingResultSlot = new Result(x + 124, y + 35));

		for(int i = 0; i < 3; ++i) {
			for(int j = 0; j < 3; ++j) {
				this.addSlot(new SlotCrafting(craftMatrix, j + i * 3, x + 30 + j * 18, y + 17 + i * 18));
			}
		}
	}

	private class Result extends ResultSlot {

		public Result(int x, int y) {
			super(pinv.player, craftMatrix, craftResult, 0, x, y);
		}

		@Override
		public void onTake(Player thePlayer, ItemStack stack) {
			this.checkTakeAchievements(stack);
			if (!pinv.player.getCommandSenderWorld().isClientSide) {
				craft(thePlayer);
			}
		}
	}

	@Override
	protected void addStorageSlots() {
		addStorageSlots(5, 8, 18);
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slotIn) {
		return slotIn.container != craftResult && super.canTakeItemForPickAll(stack, slotIn);
	}

	@Override
	public ItemStack shiftClickItems(Player playerIn, int index) {
		ItemStack itemstack = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem()) {
			ItemStack itemstack1 = slot.getItem();
			itemstack = itemstack1.copy();
			if (index == 0) {
				if(te == null)return ItemStack.EMPTY;

				if (!canCraft() || !this.moveItemStackTo(itemstack1, 10, 46, true)) {
					return ItemStack.EMPTY;
				}

				slot.onQuickCraft(itemstack1, itemstack);

				if (itemstack1.isEmpty()) {
					slot.set(ItemStack.EMPTY);
				} else {
					slot.setChanged();
				}

				if (itemstack1.getCount() == itemstack.getCount()) {
					return ItemStack.EMPTY;
				}

				slot.onTake(playerIn, itemstack1);
				if (index == 0) {
					playerIn.drop(itemstack1, false);
				}

				return itemstack;
			} else if (index > 0 && index < 10) {
				if(te == null)return ItemStack.EMPTY;
				ItemStack stack = te.pushStack(itemstack);
				slot.set(stack);
				if (!playerIn.level().isClientSide)
					broadcastChanges();
			}
			slot.onTake(playerIn, itemstack1);
		}
		return ItemStack.EMPTY;
	}

	private void onCraftingMatrixChangedInternal() {
		if (refillingGrid)return;
		CraftingInput input = craftMatrix.asCraftInput();
		if (currentRecipe.isEmpty() || !currentRecipe.get().value().matches(input, pinv.player.level())) {
			currentRecipe = getRecipe(input);
		}
		if (currentRecipe.isEmpty()) {
			craftResult.setItem(0, ItemStack.EMPTY);
		} else {
			craftResult.setItem(0, currentRecipe.get().value().assemble(input, pinv.player.level().registryAccess()));
		}
		craftResult.setRecipeUsed(currentRecipe.orElse(null));
		onCraftMatrixChanged();
	}

	public void onCraftMatrixChanged() {
		for (int i = 0; i < slots.size(); ++i) {
			Slot slot = slots.get(i);

			if (slot instanceof SlotCrafting || slot == craftingResultSlot) {
				for (ContainerListener listener : listeners) {
					if (listener instanceof ServerPlayer) {
						((ServerPlayer) listener).connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), i, slot.getItem()));
					}
				}
			}
		}
	}

	@Override
	public boolean clickMenuButton(Player playerIn, int id) {
		if(te != null && id == 0)
			clearCraftingForPlayer(playerIn);
		else if(te != null && id == 1)
			polymorphUpdate(playerIn);
		else super.clickMenuButton(playerIn, id);
		return false;
	}

	@Override
	public void fillCraftSlotsStackedContents(StackedContents itemHelperIn) {
		this.craftMatrix.fillStackedContents(itemHelperIn);
		if(te != null)sync.fillStackedContents(itemHelperIn);
		else itemList.forEach(e -> {
			itemHelperIn.accountSimpleStack(e.getActualStack());
		});
	}

	@Override
	public void clearCraftingContent() {
		this.craftMatrix.clearContent();
		this.craftResult.clearContent();
		this.currentRecipe = Optional.empty();
		onCraftingMatrixChangedInternal();
	}

	@Override
	public boolean recipeMatches(RecipeHolder<CraftingRecipe> recipeHolder) {
		return recipeHolder.value().matches(this.craftMatrix.asCraftInput(), this.pinv.player.level());
	}

	@Override
	public int getResultSlotIndex() {
		return 0;
	}

	@Override
	public int getGridWidth() {
		return this.craftMatrix.getWidth();
	}

	@Override
	public int getGridHeight() {
		return this.craftMatrix.getHeight();
	}

	@Override
	public int getSize() {
		return 10;
	}

	@Override
	public java.util.List<RecipeBookCategories> getRecipeBookCategories() {
		return Lists.newArrayList(RecipeBookCategories.CRAFTING_SEARCH, RecipeBookCategories.CRAFTING_EQUIPMENT, RecipeBookCategories.CRAFTING_BUILDING_BLOCKS, RecipeBookCategories.CRAFTING_MISC, RecipeBookCategories.CRAFTING_REDSTONE);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void handlePlacement(boolean p_40119_, RecipeHolder<?> p_300860_, ServerPlayer p_40121_) {
		if (p_300860_.value() instanceof CraftingRecipe && te != null && beginRecipeFill()) {
			new TerminalCraftingFiller(this, p_40121_)
					.placeRecipe((RecipeHolder<CraftingRecipe>) p_300860_);
		}
	}

	@Override
	public void receive(CompoundTag message) {
		super.receive(message);
		if(message.contains("fill")) {
			var id = ResourceLocation.tryParse(message.getString("fill"));
			if (id != null) {
				var recipe = pinv.player.level().getRecipeManager().byKey(id).orElse(null);
				if (recipe != null && recipe.value() instanceof CraftingRecipe
						&& te != null && beginRecipeFill()) {
					@SuppressWarnings("unchecked")
					RecipeHolder<CraftingRecipe> craftingRecipe = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) recipe;
					new TerminalCraftingFiller(this, pinv.player).placeRecipe(craftingRecipe);
				}
			}
		}
	}

	private boolean beginRecipeFill() {
		long gameTime = pinv.player.level().getGameTime();
		if (lastRecipeFillGameTime == gameTime)return false;
		lastRecipeFillGameTime = gameTime;
		return true;
	}

	@Override
	public List<TerminalItemStack> getStoredItems() {
		return itemList;
	}

	public Slot getCraftingResultSlot() {
		return craftingResultSlot;
	}

	public List<Slot> getInputGridSlots() {
		return slots.subList(1, 10);
	}

	@Override
	public boolean isSmartSearch() {
		return (searchType & 8) == 0;
	}

	private Optional<RecipeHolder<CraftingRecipe>> getRecipe(CraftingInput input) {
		if (StorageMod.polymorph && polymorphPlayer != null) {
			Player player = polymorphPlayer.get();
			if (player != null)
				return PolymorphHelper.getRecipe(player, RecipeType.CRAFTING, input, player.level());
		}
		return pinv.player.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, pinv.player.level());
	}

	private void craft(Player player) {
		if (te == null || currentRecipe.isEmpty())return;
		resetCraftingCooldown();
		CraftingInput.Positioned positioned = craftMatrix.asPositionedCraftInput();
		CraftingInput input = positioned.input();
		NonNullList<ItemStack> remainder = currentRecipe.get().value().getRemainingItems(input);
		boolean playerInvUpdate = false;
		refillingGrid = true;
		int x = positioned.left();
		int y = positioned.top();
		for (int row = 0; row < input.height(); row++) {
			for (int col = 0; col < input.width(); col++) {
				int slotIndex = col + x + (row + y) * craftMatrix.getWidth();
				ItemStack slot = craftMatrix.getItem(slotIndex);
				ItemStack oldItem = slot.copy();
				ItemStack rem = remainder.get(col + row * input.width());
				if (!slot.isEmpty()) {
					craftMatrix.removeItem(slotIndex, 1);
					slot = craftMatrix.getItem(slotIndex);
				}
				if (slot.isEmpty() && !oldItem.isEmpty()) {
					StoredItemStack stored = te.pullStack(new StoredItemStack(oldItem), 1);
					if (stored == null && (modes & 0x20) != 0) {
						for (int invSlot = 0; invSlot < player.getInventory().getContainerSize(); invSlot++) {
							ItemStack invStack = player.getInventory().getItem(invSlot);
							if (ItemStack.isSameItemSameComponents(oldItem, invStack)) {
								ItemStack removed = player.getInventory().removeItem(invSlot, 1);
								if (!removed.isEmpty()) {
									stored = new StoredItemStack(removed, 1);
									playerInvUpdate = true;
									break;
								}
							}
						}
					}
					if (stored != null) {
						craftMatrix.setItem(slotIndex, stored.getActualStack());
						slot = craftMatrix.getItem(slotIndex);
					}
				}
				if (rem.isEmpty())continue;
				if (slot.isEmpty()) {
					craftMatrix.setItem(slotIndex, rem);
					continue;
				}
				rem = te.pushStack(rem);
				if (rem.isEmpty())continue;
				if (player.getInventory().add(rem))continue;
				player.drop(rem, false);
			}
		}
		refillingGrid = false;
		onCraftingMatrixChangedInternal();
		craftingCooldown += craftResult.getItem(0).getCount();
		if (playerInvUpdate)player.containerMenu.broadcastChanges();
	}

	void clearCraftingForPlayer(Player player) {
		if (te == null)return;
		for (int i = 0; i < craftMatrix.getContainerSize(); i++) {
			ItemStack stack = craftMatrix.removeItemNoUpdate(i);
			if (!stack.isEmpty()) {
				StoredItemStack remainder = te.isRemoved() ? new StoredItemStack(stack)
						: te.pushStack(new StoredItemStack(stack));
				if (remainder != null) {
					ItemStack returned = remainder.getActualStack();
					if (!player.getInventory().add(returned) && !returned.isEmpty())te.dropItem(returned);
				}
			}
		}
		currentRecipe = Optional.empty();
		craftResult.clearContent();
		onCraftingMatrixChangedInternal();
	}

	public void dropCraftingForRemoval() {
		if (te == null)return;
		for (int i = 0; i < craftMatrix.getContainerSize(); i++) {
			ItemStack stack = craftMatrix.removeItemNoUpdate(i);
			if (!stack.isEmpty())te.dropItem(stack);
		}
		currentRecipe = Optional.empty();
		craftResult.clearContent();
	}

	private boolean canCraft() {
		resetCraftingCooldown();
		return craftingCooldown + craftResult.getItem(0).getCount() <= craftResult.getItem(0).getMaxStackSize();
	}

	private void resetCraftingCooldown() {
		long gameTime = pinv.player.level().getGameTime();
		if (craftingCooldownGameTime != gameTime) {
			craftingCooldownGameTime = gameTime;
			craftingCooldown = 0;
		}
	}

	private void polymorphUpdate(Player player) {
		polymorphPlayer = new WeakReference<>(player);
		currentRecipe = Optional.empty();
		onCraftingMatrixChangedInternal();
	}

	public StoredItemStack pullStack(StoredItemStack stack, long max) {
		return te == null ? null : te.pullStack(stack, max);
	}

	public StoredItemStack pushStack(StoredItemStack stack) {
		return te == null ? null : te.pushStack(stack);
	}

	public Iterable<StoredItemStack> getCachedCraftingStacks() {
		return te == null ? List.of() : te.getCachedCraftingStacks();
	}

	public Iterable<StoredItemStack> getFreshCraftingStacks() {
		return te == null ? List.of() : te.getFreshCraftingStacks();
	}

	public void setCraftSlot(int x, int y, ItemStack stack) {
		craftMatrix.setItem(x + y * 3, stack);
	}

	public void importLegacyCrafting(CraftingContainer legacy) {
		for (int i = 0; i < Math.min(craftMatrix.getContainerSize(), legacy.getContainerSize()); i++) {
			ItemStack stack = legacy.removeItemNoUpdate(i);
			if (!stack.isEmpty())craftMatrix.setItem(i, stack);
		}
	}
}
