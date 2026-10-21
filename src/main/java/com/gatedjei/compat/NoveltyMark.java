package com.gatedjei.compat;

import com.gatedjei.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Builds the marker that says "you have never held this before".
 *
 * <h2>What the marker is for</h2>
 *
 * <p>A pickup display already tells the player "you picked up 3 dirt". That is a different and far
 * more common fact than the one this mod knows, which is "this is the first dirt you have ever
 * held". The two are complementary, so the marker is deliberately additive: it prefixes a glyph and
 * sets a colour, and it leaves the host's own text — including its count and its rarity colour —
 * alone.
 *
 * <h2>Why the colour is set explicitly</h2>
 *
 * <p>Pick Up Notifier composes its row as {@code Component.empty().append(name).setStyle(rowStyle)},
 * i.e. our value becomes a child and the host then styles the parent. Vanilla resolves style
 * per-field with the child winning any field the child specifies, so an explicitly specified colour
 * on our wrapper survives the host's {@code setStyle} while everything we do not specify still
 * inherits from the row. That is why this returns a styled wrapper rather than a plain
 * concatenation, and why it must not use {@link ChatFormatting} shorthand that the host might
 * overwrite.
 *
 * <p>An item whose own name already specifies a colour (a rarity colour, or a renamed item) keeps
 * it, for the same child-wins reason. That is intended: the glyph carries the novelty signal, so
 * rarity information is not worth destroying to restate it.
 */
public final class NoveltyMark {

    /**
     * A dinkus-free, widely-available glyph. It is in Minecraft's default font and reads as "new"
     * without looking like a vanilla UI element.
     */
    public static final String DEFAULT_GLYPH = "✦"; // BLACK FOUR POINTED STAR

    /** Cyan. Distinct from every vanilla rarity colour, legible on both chat and tooltip backdrops. */
    public static final int DEFAULT_COLOR = 0x55FFFF;

    private NoveltyMark() {}

    /**
     * Wrap a host's entry name so it reads as a first-ever sighting.
     *
     * <p>Never throws and never returns null for a non-null input: this runs inside another mod's
     * render path, where an exception would take their HUD down with it.
     */
    public static Component decorate(Component name) {
        if (name == null) {
            return null;
        }
        try {
            String glyph = Config.orDefault(Config.NOVELTY_GLYPH, DEFAULT_GLYPH);
            int rgb = Config.orDefault(Config.NOVELTY_COLOR, DEFAULT_COLOR);

            MutableComponent out = Component.empty();
            if (!glyph.isEmpty()) {
                out.append(Component.literal(glyph)).append(" ");
            }
            out.append(name);
            // withStyle(Style) merges onto this component only; children keep their own fields.
            return out.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
        } catch (Throwable t) {
            return name;
        }
    }
}
