package me.kismeria.smpcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel advances of the vanilla default font (font-widths.txt, generated from the 26.3 client jar).
 * Lets the server place text precisely in the action bar without a resource pack.
 */
public final class FontWidths {

    /** Characters outside the bitmap fonts come from unifont; their width is a guess. */
    private static final int UNKNOWN = 6;
    private static final Map<Integer, Integer> ADVANCES = new HashMap<>();

    static {
        InputStream in = FontWidths.class.getResourceAsStream("/font-widths.txt");
        if (in != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.trim().split(" ");
                    int from = Integer.parseInt(parts[0], 16);
                    int to = Integer.parseInt(parts[1], 16);
                    int advance = Integer.parseInt(parts[2]);
                    for (int cp = from; cp <= to; cp++) {
                        ADVANCES.put(cp, advance);
                    }
                }
            } catch (Exception ignored) {
                // no table: every width falls back to UNKNOWN
            }
        }
    }

    private FontWidths() {
    }

    public static int advance(int codepoint, boolean bold) {
        int advance = ADVANCES.getOrDefault(codepoint, UNKNOWN);
        return codepoint == 0x200C ? 0 : advance + (bold ? 1 : 0);
    }

    public static int width(String text, boolean bold) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += advance(cp, bold);
            i += Character.charCount(cp);
        }
        return width;
    }

    public static int width(Component component) {
        return width(component, false);
    }

    private static int width(Component component, boolean parentBold) {
        TextDecoration.State state = component.style().decoration(TextDecoration.BOLD);
        boolean bold = state == TextDecoration.State.NOT_SET ? parentBold : state == TextDecoration.State.TRUE;
        int width = 0;
        if (component instanceof TextComponent text) {
            width += width(text.content(), bold);
        } else if (component instanceof TranslatableComponent translatable) {
            // the client translates it; the fallback or key is the best guess the server has
            String guess = translatable.fallback() != null ? translatable.fallback() : translatable.key();
            width += width(guess, bold);
        }
        for (Component child : component.children()) {
            width += width(child, bold);
        }
        return width;
    }
}
