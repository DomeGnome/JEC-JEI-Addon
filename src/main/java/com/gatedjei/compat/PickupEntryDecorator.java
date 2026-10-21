package com.gatedjei.compat;

import com.gatedjei.GatedJei;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The landing pad every compat mixin calls into, and the only place the two integrations differ.
 *
 * <p>Mirrors {@link com.gatedjei.hook.DiscoveryHooks} in intent: the mixins are one-line shims and
 * all the thinking lives here, outside any package Mixin claims. Nothing in this class or anything
 * it touches names a third-party type, so it classloads with none of those mods installed.
 *
 * <h2>Decide at creation, read at render</h2>
 *
 * <p>Novelty is evaluated once, in the host entry's constructor, and remembered against that entry.
 * Reading it at render time instead would be wrong in both hosts for the same underlying reason:
 * they resolve a row's label once and cache it, and the cache can be invalidated and recomputed
 * later (Pick Up Notifier clears it on {@code mergeWith}/{@code resetEntry}; Loot Journal rebuilds
 * on a merge). A render-time question would then be asked again several seconds later, after the
 * discovery had been applied and the claim had expired, and the row would silently lose its marker
 * mid-display. Freezing the verdict at construction makes the row stable for its whole life.
 *
 * <p>It is also the only moment with the right answer available. Both hosts build the entry
 * synchronously inside the pickup packet handler, before our observation queue has drained — which
 * is exactly the window {@link NoveltySightings#claim} is built to answer in.
 *
 * <h2>Why a side map and not a {@code @Unique} field</h2>
 *
 * <p>The tidier Mixin idiom is a {@code @Unique} field on the target plus a {@code @Shadow} of the
 * host's own stack field. Both are avoided deliberately: the mixins here are {@code @Pseudo} with
 * string targets, there is no game to run offline, and neither {@code @Shadow} resolution nor field
 * injection on a soft target could be verified before shipping. Passing {@code this} as an
 * {@link Object} and keeping the flag on our side uses only the one Mixin feature already proven in
 * this project — a plain {@code @Inject} — and fails to a no-op rather than to a mixin apply error.
 *
 * <p>The map holds weak keys, so an entry that the host drops is collected with it. In practice it
 * holds at most a handful of rows for a few seconds, since only <em>novel</em> entries are recorded
 * at all and a first-ever sighting is rare by construction.
 */
public final class PickupEntryDecorator {

    /**
     * Host entries we have marked. Weak keys; identity semantics in practice, since a display entry
     * is a plain object with no {@code equals} override. Synchronized because a host is free to
     * build entries off the render thread even if neither of these two does today.
     */
    /**
     * Novelty keys that a third-party row has actually badged.
     *
     * <p>This is what makes supersession per-notice rather than global. Our own notification channel
     * must stay live even when a pickup mod is installed, because those mods only ever display
     * <em>ground pickups</em> — crafting output, chest withdrawals, villager trades, {@code /give}
     * and {@code /gatedjei discoverheld} are all invisible to them. Suppressing our whole channel
     * because one is present would leave those routes announced by nobody.
     *
     * <p>So we record the specific kinds they badged, and only those are skipped.
     */
    private static final Set<Object> EXTERNALLY_MARKED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final Map<Object, Boolean> MARKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean warnedFailure;

    private PickupEntryDecorator() {}

    /**
     * Called from a host entry's constructor: decide now whether this row is a first-ever sighting.
     *
     * <p>Claims the sighting if it is, which is what stops our own fallback toast from announcing
     * the same thing. Does nothing at all when the integration is switched off, so the config
     * toggle also means "do not consume the novelty" and our own notification still gets it.
     *
     * @param entry  the host's entry object, as {@code (Object) this} from the mixin
     * @param stack  the stack the row is about
     * @param host   which integration is calling, for the config gate
     */
    public static void markIfNovel(Object entry, ItemStack stack, ExternalDisplay host) {
        try {
            if (entry == null || host == null || !host.active()) {
                return;
            }
            if (!NoveltySightings.get().claim(stack)) {
                return;
            }
            MARKED.put(entry, Boolean.TRUE);
            EXTERNALLY_MARKED.add(NoveltySightings.keyOf(stack));
        } catch (Throwable t) {
            warnOnce(host, t);
        }
    }

    /**
     * As {@link #markIfNovel(Object, ItemStack, ExternalDisplay)}, but only for the local player.
     *
     * <p>Loot Journal also displays <em>other</em> players' pickups. Marking those would be wrong
     * twice over: the row would claim the player has never held an item they may well own, and the
     * claim would consume the novelty so their own first pickup of it later went unmarked. Pick Up
     * Notifier filters to the local player itself, but this costs an identity comparison and is not
     * worth trusting a third party for.
     */
    public static void markIfNovelFor(Object entry, Player player, ItemStack stack, ExternalDisplay host) {
        if (!isLocalPlayer(player)) {
            return;
        }
        markIfNovel(entry, stack, host);
    }

    /**
     * Called from the host's "what is this row called" method: add the marker if we marked the row.
     *
     * <p>Returns {@code name} unchanged on absolutely any doubt. A decoration that silently does
     * nothing is a cosmetic disappointment; an exception thrown out of a HUD render is a broken
     * game for a feature nobody asked to be load-bearing.
     */
    public static Component decorate(Object entry, Component name, ExternalDisplay host) {
        try {
            if (entry == null || name == null || host == null || !host.active()) {
                return name;
            }
            if (MARKED.get(entry) != Boolean.TRUE) {
                return name;
            }
            return NoveltyMark.decorate(name);
        } catch (Throwable t) {
            warnOnce(host, t);
            return name;
        }
    }

    /** Visible for testing / diagnostics. */
    /**
     * Whether a third-party row has already badged this novelty key.
     *
     * <p>Consulted per notice by {@code ComprehensionNotices}, so that a ground pickup their row
     * already marked is announced once, while every route they cannot see still gets our notice.
     */
    public static boolean wasMarkedExternally(Object noveltyKey) {
        return noveltyKey != null && EXTERNALLY_MARKED.contains(noveltyKey);
    }

    public static int markedEntryCount() {
        return MARKED.size();
    }

    /** Advance the claim clock and expire stale claims. Called once per client tick. */
    public static void tick() {
        NoveltySightings.get().tick();
    }

    /** Drop every remembered mark and claim. Called at session boundaries. */
    public static void clear() {
        MARKED.clear();
        EXTERNALLY_MARKED.clear();
        NoveltySightings.get().clear();
    }

    private static boolean isLocalPlayer(Player player) {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && player != null && player == mc.player;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void warnOnce(ExternalDisplay host, Throwable t) {
        if (!warnedFailure) {
            warnedFailure = true;
            GatedJei.LOGGER.warn("Pickup-display decoration for '{}' failed and is now inert: {}",
                    host == null ? "?" : host.modId(), t.toString());
        }
    }
}
