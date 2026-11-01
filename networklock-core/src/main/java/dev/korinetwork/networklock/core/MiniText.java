package dev.korinetwork.networklock.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

/**
 * Message formatting. {@code &}-codes are the default style; MiniMessage tags are also accepted.
 * For platforms without MiniMessage (BungeeCord) the colour and decoration subset is converted to
 * legacy section-sign text, and unknown or interactive tags (click, hover, insertion) are dropped,
 * so a message can never carry an action to a client.
 */
public final class MiniText {

    private static final Map<String, Character> COLORS = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'),
            Map.entry("dark_aqua", '3'), Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'),
            Map.entry("gold", '6'), Map.entry("gray", '7'), Map.entry("grey", '7'), Map.entry("dark_gray", '8'),
            Map.entry("dark_grey", '8'), Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'),
            Map.entry("red", 'c'), Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'));

    private static final Map<String, Character> DECORATIONS = Map.ofEntries(
            Map.entry("bold", 'l'), Map.entry("b", 'l'), Map.entry("italic", 'o'), Map.entry("i", 'o'),
            Map.entry("em", 'o'), Map.entry("underlined", 'n'), Map.entry("u", 'n'),
            Map.entry("strikethrough", 'm'), Map.entry("st", 'm'), Map.entry("obfuscated", 'k'), Map.entry("obf", 'k'));

    private static final String[] AMPERSAND_TAGS = new String[128];

    static {
        String[] colors = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < 16; i++) AMPERSAND_TAGS["0123456789abcdef".charAt(i)] = "<reset><" + colors[i] + ">";
        AMPERSAND_TAGS['l'] = "<bold>";
        AMPERSAND_TAGS['o'] = "<italic>";
        AMPERSAND_TAGS['n'] = "<underlined>";
        AMPERSAND_TAGS['m'] = "<strikethrough>";
        AMPERSAND_TAGS['k'] = "<obfuscated>";
        AMPERSAND_TAGS['r'] = "<reset>";
    }

    private MiniText() {}

    /**
     * Rewrites {@code &}-codes ({@code &a}, {@code &l}, {@code &#ff8800}) as MiniMessage tags, so
     * messages may use either style or both. A colour code resets decorations, as in legacy text.
     */
    public static String ampersandToMini(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '&' && i + 8 <= text.length() && text.charAt(i + 1) == '#'
                    && text.substring(i + 2, i + 8).matches("[0-9A-Fa-f]{6}")) {
                out.append("<reset><#").append(text, i + 2, i + 8).append('>');
                i += 8;
                continue;
            }
            if (c == '&' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                String tag = code < 128 ? AMPERSAND_TAGS[code] : null;
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** {@code &}-codes and/or the MiniMessage subset, as legacy section-sign text. */
    public static String anyToLegacy(String text) {
        return toLegacy(ampersandToMini(text));
    }

    public static String toLegacy(String mini) {
        StringBuilder out = new StringBuilder(mini.length());
        Deque<String> open = new ArrayDeque<>(); // legacy codes of open tags, innermost first
        int i = 0;
        while (i < mini.length()) {
            char c = mini.charAt(i);
            if (c == '\\' && i + 1 < mini.length() && mini.charAt(i + 1) == '<') {
                out.append('<');
                i += 2;
                continue;
            }
            int end = c == '<' ? mini.indexOf('>', i) : -1;
            if (end < 0) {
                if (c != '§') out.append(c); // never pass raw legacy codes through
                i++;
                continue;
            }
            String tag = mini.substring(i + 1, end).toLowerCase(Locale.ROOT).trim();
            i = end + 1;
            if (tag.startsWith("/")) {
                if (!open.isEmpty()) open.pop();
                out.append("§r");
                java.util.Iterator<String> it = open.descendingIterator();
                while (it.hasNext()) out.append(it.next());
                continue;
            }
            if (tag.equals("reset")) {
                open.clear();
                out.append("§r");
                continue;
            }
            String code = code(tag);
            if (code != null) {
                if (code.length() > 2 || isColor(code)) open.clear(); // a colour resets decorations, as in legacy text
                open.push(code);
                out.append(code);
            }
            // anything else (click, hover, gradients, placeholders) is dropped
        }
        return out.toString();
    }

    private static boolean isColor(String code) {
        return code.length() == 2 && "0123456789abcdef".indexOf(code.charAt(1)) >= 0;
    }

    private static String code(String tag) {
        if (tag.startsWith("color:") || tag.startsWith("c:")) tag = tag.substring(tag.indexOf(':') + 1);
        Character named = COLORS.get(tag);
        if (named != null) return "§" + named;
        Character deco = DECORATIONS.get(tag);
        if (deco != null) return "§" + deco;
        if (tag.matches("#[0-9a-f]{6}")) {
            StringBuilder hex = new StringBuilder("§x");
            for (int k = 1; k < 7; k++) hex.append('§').append(tag.charAt(k));
            return hex.toString();
        }
        return null;
    }
}
