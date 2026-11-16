package org.motiquies.patchouliicons;

import vazkii.patchouli.client.book.*;
import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class IconContractTest {
    static boolean baseline;
    static BookIcon create(String icon, String flag) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("icon", icon);
        json.addProperty("flag", flag);
        if (!baseline && !(boolean) Class.forName("org.motiquies.patchouliicons.FlaggedBookIcons")
                .getMethod("canLoad", JsonObject.class).invoke(null, json)) return null;
        return BookIcon.from(icon);
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        baseline = args.length > 0 && args[0].equals("baseline");
        ParserState.reset();
        BookIcon disabled = create("absent:compat_ingot", "mod:absent");
        require(ParserState.parses == 0 && ParserState.warnings == 0,
                "Disabled optional entry parsed absent item before its flag was applied");
        require(disabled == null, "Disabled entry must keep the upstream loadEntry null result");
        ParserState.reset();
        create("absent:broken_required_item", "");
        require(ParserState.parses == 1 && ParserState.warnings == 1,
                "A genuine unflagged resource error must still reach the original parser immediately");
        ParserState.reset();
        create("absent:broken_enabled_item", "mod:present");
        require(ParserState.parses == 1 && ParserState.warnings == 1,
                "An enabled integration error must still reach the original parser immediately");
        ParserState.reset();
        create("minecraft:stone", "mod:present").render(null, 2, 3);
        require(ParserState.parses == 1 && ParserState.renders == 1 && ParserState.warnings == 0,
                "Enabled valid icon behavior changed");
        JsonObject unflagged = new JsonObject();
        unflagged.addProperty("icon", "minecraft:stone");
        require((boolean)Class.forName("org.motiquies.patchouliicons.FlaggedBookIcons")
                .getMethod("canLoad", JsonObject.class).invoke(null, unflagged), "Missing flag must remain enabled");
        var callbackMethod = java.util.Arrays.stream(Class.forName(
                "org.motiquies.patchouliicons.mixin.BookEntryIconMixin").getDeclaredMethods())
                .filter(m -> m.getName().equals("motiquies$checkFlagBeforeParsing")).findFirst().orElseThrow();
        callbackMethod.setAccessible(true);
        for (String flag : new String[]{"mod:absent", "mod:present", ""}) {
            JsonObject json = new JsonObject(); json.addProperty("flag", flag);
            var callback = new CallbackInfoReturnable<BookEntry>("loadEntry", true);
            callbackMethod.invoke(null, null, null, null, null, null, callback,
                    new BookContentLoader.LoadResult(json, "upstream-author"));
            require(callback.isCancelled() == flag.equals("mod:absent"),
                    "Actual injection callback did not preserve the entry flag decision: " + flag);
            if (callback.isCancelled()) require(callback.getReturnValue() == null, "Disabled entry result changed");
        }
        System.out.println("PASS: disabled entry, required errors, enabled errors, valid icon, absent flag");
        System.out.println("PASS: actual injection callback cancellation contracts");
    }
}
