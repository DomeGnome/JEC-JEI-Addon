package com.gatedjei.notify;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.discovery.DiscoveryState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Our own "you comprehended something new" notification.
 *
 * <h2>Off by default, and a fallback by design</h2>
 *
 * <p>{@code notifyOnNewComprehension} defaults to {@code false}. This exists for players who run no
 * pickup-notification mod at all; where one is installed, that mod's own display is the better place
 * to mark newly comprehended items and this should stay off.
 *
 * <p>Nothing here participates in discovery. It is a pure observer of
 * {@link DiscoveryState}'s delta listeners, so switching it on or off cannot change what gets
 * discovered — only whether the player is told.
 *
 * <h2>What fires it</h2>
 *
 * <p>Only genuinely new comprehension: the delta listeners are called with items that were not in
 * the discovery set a moment ago, so an ordinary pickup of something already known produces nothing.
 * Batching, rate limiting and the bulk threshold live in {@link NoticeFeed}; this class is the
 * wiring and the drawing decision.
 *
 * <p>Fluids are deliberately not subscribed: a fluid is only ever comprehended through a container
 * item (see {@code discoverFluidsByBucket}), so the bucket's own notice already covers it and a
 * second one would be a duplicate. Subtype variants <em>are</em> subscribed, because in granular
 * mode a new potion or enchanted book is exactly the uncommon, interesting late-game discovery this
 * notification is for — but a variant key cannot be turned back into a decorated stack, so such a
 * notice shows the base item's icon and name.
 */
@EventBusSubscriber(modid = GatedJei.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ComprehensionNotices {

    private static final NoticeFeed FEED = new NoticeFeed();
    private static boolean warnedFailure;

    private ComprehensionNotices() {}

    /** The batching feed, exposed for tests and for commands that need to go quiet. */
    public static NoticeFeed feed() {
        return FEED;
    }

    /**
     * Subscribe to discovery deltas. Called once from the mod constructor.
     *
     * <p>Registered unconditionally, even though the feature defaults to off, so that flipping the
     * config on takes effect immediately instead of needing a restart. The cost when off is one
     * boolean read per delta.
     */
    public static void install() {
        DiscoveryState state = DiscoveryState.get();
        state.addListener(ComprehensionNotices::onNewItems);
        state.addVariantListener(ComprehensionNotices::onNewVariants);
    }

    /** Run {@code body} with notifications suppressed. For bulk debug commands. */
    public static void runQuietly(Runnable body) {
        FEED.beginQuiet();
        try {
            body.run();
        } finally {
            FEED.endQuiet();
        }
    }

    // ---- discovery listeners ----

    private static void onNewItems(Set<Item> newlyDiscovered) {
        if (!enabled()) {
            return;
        }
        // A listener runs inside DiscoveryState.discoverAll, which is iterating its listener list.
        // Throwing here would skip RecipeGate and leave JEI stale, so nothing escapes.
        try {
            List<Item> toAnnounce = notSupersededBy(newlyDiscovered);
            if (!toAnnounce.isEmpty()) {
                FEED.offer(toAnnounce);
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void onNewVariants(Set<String> newlyDiscovered) {
        if (!enabled()) {
            return;
        }
        try {
            // Filter on the variant key, since that is the granularity the row badged.
            List<Item> bases = new ArrayList<>();
            for (String key : notSupersededBy(newlyDiscovered)) {
                baseItemOf(key).ifPresent(bases::add);
            }
            if (!bases.isEmpty()) {
                FEED.offer(bases);
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /**
     * Recover the item a variant key was built from.
     *
     * <p>{@code SubtypeKeys} formats a key as {@code <item id>} or {@code <item id>#<subtype data>},
     * so the registry id is everything before the first {@code '#'}.
     */
    private static Optional<Item> baseItemOf(String variantKey) {
        if (variantKey == null || variantKey.isEmpty()) {
            return Optional.empty();
        }
        int hash = variantKey.indexOf('#');
        String id = hash < 0 ? variantKey : variantKey.substring(0, hash);
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(location);
    }

    // ---- lifecycle ----

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        FEED.reset();
        // The join sweep re-derives discovery from an inventory the player has been carrying all
        // along. That is bookkeeping, not news, and it can cover a hundred items in one drain.
        FEED.suppressForTicks(Config.orDefault(Config.NOTIFICATION_JOIN_QUIET_TICKS, 100));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        FEED.reset();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            // Ticked even when disabled or with nothing pending: the tick count is what expires the
            // join suppression window and the rate limit.
            FEED.tick(settings(), ComprehensionNotices::present);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    // ---- presentation ----

    private static void present(NoticeFeed.Notice notice) {
        if (!enabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || DiscoveryState.get().loadedKey() == null) {
            return;
        }
        List<ItemStack> stacks = toStacks(notice.icons());
        if (stacks.isEmpty()) {
            return;
        }
        switch (Config.orDefault(Config.NOTIFICATION_STYLE, Config.NotificationStyle.TOAST)) {
            case TOAST -> ComprehensionToast.show(client.getToasts(), stacks, notice.total(), maxIcons());
            // displayClientMessage(..., true) is the action bar; false puts it in chat.
            case ACTION_BAR -> player.displayClientMessage(summary(stacks, notice.total()), true);
            case CHAT -> player.displayClientMessage(summary(stacks, notice.total()), false);
        }
    }

    /**
     * Show a notice immediately, bypassing batching, the rate limit and the master switch.
     *
     * <p>Backs {@code /gatedjei notifytest}. Rendering cannot be verified offline, so there has to be
     * a way to put the real toast on screen on demand rather than waiting for a discovery to happen.
     *
     * @return false if there was no player or nothing to show
     */
    public static boolean presentNow(List<Item> items, int total) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || items == null || items.isEmpty()) {
            return false;
        }
        List<ItemStack> stacks = toStacks(items);
        if (stacks.isEmpty()) {
            return false;
        }
        int reported = Math.max(total, stacks.size());
        switch (Config.orDefault(Config.NOTIFICATION_STYLE, Config.NotificationStyle.TOAST)) {
            case TOAST -> ComprehensionToast.show(client.getToasts(), stacks, reported, maxIcons());
            case ACTION_BAR -> player.displayClientMessage(summary(stacks, reported), true);
            case CHAT -> player.displayClientMessage(summary(stacks, reported), false);
        }
        return true;
    }

    private static Component summary(List<ItemStack> stacks, int total) {
        if (total == 1) {
            return Component.translatable("gatedjei.notice.single", stacks.get(0).getHoverName());
        }
        return Component.translatable("gatedjei.notice.multiple", total);
    }

    private static List<ItemStack> toStacks(List<Item> items) {
        List<ItemStack> out = new ArrayList<>(items.size());
        for (Item item : items) {
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // ---- config ----

    /**
     * Whether to speak at all.
     *
     * <p>One condition only: the player's own toggle. Supersession is deliberately NOT applied
     * here, because it is per-notice — see {@link #notSupersededBy}.
     *
     * <p>This channel used to go silent whenever any supported pickup mod was installed. That was
     * wrong: those mods display <em>ground pickups only</em>, so crafting output, chest
     * withdrawals, villager trades, {@code /give} and {@code /gatedjei discoverheld} were announced
     * by nobody at all. An integration must never be the only way something is reported.
     */
    private static boolean enabled() {
        return Config.orDefault(Config.NOTIFY_ON_NEW_COMPREHENSION, false);
    }

    /**
     * Drop the novelty keys a third-party row has already badged.
     *
     * <p>This is supersession done per notice rather than per session: a ground pickup their row
     * marked is announced once, by them; everything they never see is still ours to announce. With
     * {@code notifyAlongsideExternalDisplays} set, nothing is dropped and the player gets both.
     */
    private static <T> List<T> notSupersededBy(Collection<T> keys) {
        if (Config.orDefault(Config.NOTIFY_ALONGSIDE_EXTERNAL_DISPLAYS, false)) {
            return new ArrayList<>(keys);
        }
        List<T> out = new ArrayList<>(keys.size());
        for (T key : keys) {
            if (!com.gatedjei.compat.PickupEntryDecorator.wasMarkedExternally(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static int maxIcons() {
        return Config.orDefault(Config.NOTIFICATION_MAX_ICONS, 6);
    }

    private static NoticeFeed.Settings settings() {
        return new NoticeFeed.Settings(
                Config.orDefault(Config.NOTIFICATION_COALESCE_TICKS, 20),
                Config.orDefault(Config.NOTIFICATION_MIN_INTERVAL_TICKS, 100),
                Config.orDefault(Config.NOTIFICATION_BULK_THRESHOLD, 32),
                maxIcons());
    }

    private static void warnOnce(Throwable t) {
        if (!warnedFailure) {
            warnedFailure = true;
            GatedJei.LOGGER.warn("Comprehension notifications failed and are being skipped: {}", t.toString());
        }
    }
}
