package vazkii.patchouli.client.book;

import net.minecraft.client.gui.GuiGraphics;

/** Boundary fixture: a parser must never resolve absent items in disabled entries. */
public interface BookIcon {
    void render(GuiGraphics graphics, int x, int y);
    static BookIcon from(String icon) {
        ParserState.parses++;
        ParserState.lastIcon = icon;
        if (icon.startsWith("absent:")) ParserState.warnings++;
        return (graphics, x, y) -> ParserState.renders++;
    }
}
