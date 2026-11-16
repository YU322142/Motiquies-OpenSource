package net.minecraft.world.item;
/** Only the hovered-stack boundary is replaced; no Minecraft item registries are initialized. */
public final class ItemStack {
    public final String marker;
    public ItemStack(String marker) { this.marker=marker; }
}
