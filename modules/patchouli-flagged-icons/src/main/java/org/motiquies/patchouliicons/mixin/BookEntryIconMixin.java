package org.motiquies.patchouliicons.mixin;

import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.motiquies.patchouliicons.FlaggedBookIcons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookCategory;
import vazkii.patchouli.client.book.BookContentLoader;
import vazkii.patchouli.common.book.Book;

@Mixin(targets = "vazkii.patchouli.client.book.BookContentsBuilder", remap = false)
public abstract class BookEntryIconMixin {
    @Inject(method = "loadEntry", at = @At(value = "NEW", target = "vazkii/patchouli/client/book/BookEntry"),
            cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD, require = 1)
    private static void motiquies$checkFlagBeforeParsing(Book book, BookContentLoader loader,
            ResourceLocation id, ResourceLocation resource, Function<ResourceLocation, BookCategory> categories,
            CallbackInfoReturnable<BookEntry> callback, BookContentLoader.LoadResult result) {
        if (!FlaggedBookIcons.canLoad(result.json().getAsJsonObject())) callback.setReturnValue(null);
    }
}
