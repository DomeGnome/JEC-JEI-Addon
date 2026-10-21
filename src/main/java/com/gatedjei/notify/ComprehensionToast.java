package com.gatedjei.notify;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla-style toast shown when the player comprehends something new.
 *
 * <h2>One toast, never a queue of them</h2>
 *
 * <p>{@code ToastComponent} keeps an unbounded {@code Deque} of queued toasts and shows five at a
 * time for five seconds each, so adding one toast per discovery would turn a shulker box of new
 * loot into a minute of popups. Instead there is only ever one of these alive: {@link #show} looks
 * the live instance up by {@linkplain #getToken() token} — {@code ToastComponent#getToast} searches
 * both the visible and the queued lists — and merges into it rather than queueing another. Vanilla's
 * own {@code RecipeToast} and {@code SystemToast} use the same token trick for the same reason.
 *
 * <p>Merging also restarts the display timer, so a burst that arrives over several seconds keeps one
 * toast on screen instead of stacking.
 *
 * <p>Geometry matches the 160x32 {@code toast/advancement} sprite that vanilla's advancement toast
 * draws into: a 16x16 item at (8, 8), a title at (30, 7) and one line of body text at (30, 18).
 * Reusing a vanilla sprite means this ships no textures and inherits resource-pack restyling.
 *
 * <p>Timing honours {@code ToastComponent#getNotificationDisplayTimeMultiplier()}, which is the
 * player's "Notification Time" accessibility setting, so a player who wants shorter or longer
 * popups gets them here too.
 */
public final class ComprehensionToast implements Toast {

    /**
     * Identity token for {@code ToastComponent#getToast}, which compares with {@code equals}. A
     * private object gives us exactly one slot that no other mod's toast can collide with.
     */
    private static final Object TOKEN = new Object();

    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/advancement");

    private static final long BASE_DISPLAY_MS = 5000L;
    /** Floor on how fast icons may cycle, so a capped notice does not strobe. */
    private static final long MIN_MS_PER_ICON = 700L;

    private static final int TEXT_LEFT = 30;
    private static final int TEXT_MAX_WIDTH = 125;
    private static final int TITLE_Y = 7;
    private static final int BODY_Y = 18;
    private static final int ICON_X = 8;
    private static final int ICON_Y = 8;

    /** Yellow on the advancement sprite, matching vanilla's own title colour on this background. */
    private static final int TITLE_ARGB = 0xFFFFFF55;
    private static final int BODY_ARGB = 0xFFFFFFFF;

    private static final Component TITLE = Component.translatable("gatedjei.notice.title");

    private final List<ItemStack> icons = new ArrayList<>();
    private final int maxIcons;

    private int total;
    private long lastChangedAt;
    private boolean changed;
    /** Lazily split body text; nulled whenever the contents change. */
    private List<FormattedCharSequence> bodyLines;

    private ComprehensionToast(int maxIcons) {
        this.maxIcons = Math.max(1, maxIcons);
    }

    @Override
    public Object getToken() {
        return TOKEN;
    }

    /**
     * Show, or fold into, the single comprehension toast.
     *
     * @param toasts   the client's toast component ({@code Minecraft#getToasts()})
     * @param newIcons item stacks to display, already capped by the caller
     * @param newTotal how many items this addition covers (may exceed {@code newIcons.size()})
     * @param maxIcons icon cap for a freshly created toast
     */
    public static void show(ToastComponent toasts, List<ItemStack> newIcons, int newTotal, int maxIcons) {
        if (toasts == null || newTotal <= 0 || newIcons == null || newIcons.isEmpty()) {
            return;
        }
        ComprehensionToast live = toasts.getToast(ComprehensionToast.class, TOKEN);
        if (live == null) {
            ComprehensionToast fresh = new ComprehensionToast(maxIcons);
            fresh.merge(newIcons, newTotal);
            // NeoForge fires a cancellable ToastAddEvent from here, so another mod can suppress us.
            toasts.addToast(fresh);
        } else {
            live.merge(newIcons, newTotal);
        }
    }

    private void merge(List<ItemStack> newIcons, int newTotal) {
        for (ItemStack stack : newIcons) {
            if (icons.size() >= maxIcons) {
                break;
            }
            if (stack != null && !stack.isEmpty()) {
                icons.add(stack);
            }
        }
        total += Math.max(0, newTotal);
        changed = true;
        bodyLines = null;
    }

    @Override
    public Toast.Visibility render(GuiGraphics graphics, ToastComponent toasts, long timeSinceLastVisible) {
        if (changed) {
            // Restart the dwell clock so merged-in discoveries get their own full display time.
            lastChangedAt = timeSinceLastVisible;
            changed = false;
        }
        if (icons.isEmpty() || total <= 0) {
            return Toast.Visibility.HIDE;
        }

        Font font = toasts.getMinecraft().font;
        graphics.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());
        graphics.drawString(font, TITLE, TEXT_LEFT, TITLE_Y, TITLE_ARGB, false);

        if (bodyLines == null) {
            bodyLines = font.split(body(), TEXT_MAX_WIDTH);
        }
        if (!bodyLines.isEmpty()) {
            // One line only: the sprite is 32px tall and the title already owns the top half.
            graphics.drawString(font, bodyLines.get(0), TEXT_LEFT, BODY_Y, BODY_ARGB, false);
        }

        long window = displayWindowMs(toasts);
        graphics.renderFakeItem(iconAt(timeSinceLastVisible, window), ICON_X, ICON_Y);

        return timeSinceLastVisible - lastChangedAt < window ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
    }

    /** The item's own name when there is one, otherwise the exact count. */
    private Component body() {
        if (total == 1 && !icons.isEmpty()) {
            return icons.get(0).getHoverName();
        }
        return Component.translatable("gatedjei.notice.count", total);
    }

    private long displayWindowMs(ToastComponent toasts) {
        double multiplier = toasts.getNotificationDisplayTimeMultiplier();
        if (multiplier <= 0.0) {
            return 0L; // the player asked for no dwell time; honour it
        }
        return (long) (BASE_DISPLAY_MS * multiplier);
    }

    /** Cycle through the carried icons across the display window. */
    private ItemStack iconAt(long elapsedMs, long windowMs) {
        int count = icons.size();
        if (count == 1) {
            return icons.get(0);
        }
        long perIcon = Math.max(MIN_MS_PER_ICON, windowMs / count);
        int index = (int) ((Math.max(0L, elapsedMs) / perIcon) % count);
        return icons.get(index);
    }
}
