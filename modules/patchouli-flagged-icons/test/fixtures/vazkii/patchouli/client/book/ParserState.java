package vazkii.patchouli.client.book;
public final class ParserState {
    public static int parses, warnings, renders;
    public static String lastIcon;
    public static void reset() { parses = warnings = renders = 0; lastIcon = null; }
}
