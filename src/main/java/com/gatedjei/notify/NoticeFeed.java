package com.gatedjei.notify;

import com.gatedjei.GatedJei;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Batches discovery deltas into at most one player-visible notice at a time.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link com.gatedjei.discovery.DiscoveryState} fires its listeners with a delta, and a delta can
 * be enormous: the world-join sweep comprehends a whole inventory in one drain, {@code /gatedjei
 * discoverall} comprehends the entire item registry, and emptying a shulker box can land twenty new
 * items in a single tick. A notification per item would be unusable, and on the vanilla toast system
 * it is actively hostile — {@code ToastComponent} shows five toasts at a time for five seconds each,
 * so three hundred items would be about five minutes of popups.
 *
 * <p>This class is the gate in front of that. It accumulates {@link Item}s, waits for the discovery
 * burst to go quiet, applies a rate limit and a "this is a sweep, not news" size threshold, and then
 * hands out a single {@link Notice}.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #offer} is called from a {@code DiscoveryState} listener, which runs wherever discovery
 * was applied — normally the client thread's per-tick drain, but also directly from an interaction
 * handler. All mutable state is therefore guarded by this object's monitor. {@link #tick} computes
 * the notice under the lock and releases it before calling the sink, so presentation (which touches
 * the game's GUI) never runs with the lock held.
 *
 * <p>Deliberately free of any {@code net.minecraft.client} type: the batching rule is the part worth
 * testing, and this way it is testable without a GUI.
 *
 * <p>It keeps no memory of what it has already announced, only of what is still pending. It does not
 * need one: {@code DiscoveryState} fires each item at most once per save, because an item that is
 * already in the discovery set never appears in a delta again.
 */
public final class NoticeFeed {

    /**
     * Upper bound on accumulated items.
     *
     * <p>Only reachable if notices are rate-limited while something keeps feeding discoveries in.
     * Guards against unbounded growth; normal play never approaches it.
     */
    static final int MAX_PENDING = 4096;

    /**
     * Batching tunables, supplied by the caller from config.
     *
     * @param coalesceTicks    how long the feed must be quiet before a notice is emitted, so one
     *                         burst of related discoveries becomes one notice
     * @param minIntervalTicks minimum gap between notices; discoveries made during the gap are held
     *                         for the next one rather than dropped
     * @param bulkThreshold    a notice this large is treated as a sweep and dropped entirely
     *                         ({@code 0} disables the check)
     * @param maxIcons         how many item icons a single notice carries; the count is still exact
     */
    public record Settings(int coalesceTicks, int minIntervalTicks, int bulkThreshold, int maxIcons) {
        public static final Settings DEFAULTS = new Settings(20, 100, 32, 6);

        /**
         * Hard ceiling on coalescing, so a trickle of discoveries one tick apart cannot postpone a
         * notice forever.
         */
        public int maxHoldTicks() {
            return Math.max(60, coalesceTicks * 3);
        }
    }

    /**
     * One batched notice.
     *
     * @param icons up to {@link Settings#maxIcons} items, in the order they were discovered
     * @param total how many items the notice actually covers, which may exceed {@code icons.size()}
     */
    public record Notice(List<Item> icons, int total) {}

    /** Where a finished notice goes. */
    public interface Sink {
        void present(Notice notice);
    }

    private final LinkedHashSet<Item> pending = new LinkedHashSet<>();

    private long tick;
    private long firstPendingTick;
    private long lastPendingTick;
    private long lastEmitTick;
    private boolean everEmitted;
    private long suppressedUntilTick;
    private int quietDepth;

    // ---- input ----

    /**
     * Accumulate newly comprehended items.
     *
     * <p>A no-op while suppressed or inside a {@linkplain #beginQuiet() quiet block}: those
     * discoveries still happen, they are simply not news worth interrupting the player for.
     *
     * @return true if anything was accepted
     */
    public synchronized boolean offer(Collection<Item> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        if (quietDepth > 0 || tick < suppressedUntilTick) {
            return false;
        }
        if (pending.size() >= MAX_PENDING) {
            return false;
        }
        boolean wasEmpty = pending.isEmpty();
        boolean added = false;
        for (Item item : items) {
            if (item != null && pending.add(item)) {
                added = true;
            }
        }
        if (!added) {
            // Every item was already queued. Do not extend the coalescing window for a duplicate,
            // or a repeating slot sync could hold a notice back indefinitely.
            return false;
        }
        if (wasEmpty) {
            firstPendingTick = tick;
        }
        lastPendingTick = tick;
        return true;
    }

    // ---- gating ----

    /**
     * Ignore offers for the next {@code ticks} ticks.
     *
     * <p>Used on world join: the join sweep re-derives discovery from an inventory the player has
     * been carrying all along, which is bookkeeping, not discovery.
     */
    public synchronized void suppressForTicks(int ticks) {
        long until = tick + Math.max(0, ticks);
        if (until > suppressedUntilTick) {
            suppressedUntilTick = until;
        }
    }

    /** Open a block in which discoveries produce no notice. Reentrant; pair with {@link #endQuiet}. */
    public synchronized void beginQuiet() {
        quietDepth++;
    }

    /** Close a {@link #beginQuiet()} block. Anything pending from before the block is kept. */
    public synchronized void endQuiet() {
        if (quietDepth > 0) {
            quietDepth--;
        }
    }

    public synchronized boolean isQuiet() {
        return quietDepth > 0 || tick < suppressedUntilTick;
    }

    /** Drop everything and restart the clock. Called at session boundaries. */
    public synchronized void reset() {
        pending.clear();
        tick = 0;
        firstPendingTick = 0;
        lastPendingTick = 0;
        lastEmitTick = 0;
        everEmitted = false;
        suppressedUntilTick = 0;
        quietDepth = 0;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    // ---- output ----

    /**
     * Advance one tick and present a notice if one is due.
     *
     * <p>Call this every client tick, including when nothing is pending — the tick counter is what
     * expires suppression windows and rate limits.
     */
    public void tick(Settings settings, Sink sink) {
        Notice notice = advance(settings == null ? Settings.DEFAULTS : settings);
        if (notice != null && sink != null) {
            sink.present(notice);
        }
    }

    private synchronized Notice advance(Settings settings) {
        tick++;
        if (pending.isEmpty()) {
            return null;
        }
        // Still inside the burst: wait for quiet, but not past the hold ceiling.
        if (tick - lastPendingTick < settings.coalesceTicks()
                && tick - firstPendingTick < settings.maxHoldTicks()) {
            return null;
        }
        // Rate limited: keep accumulating, emit when the gap has passed.
        if (everEmitted && tick - lastEmitTick < settings.minIntervalTicks()) {
            return null;
        }

        int total = pending.size();
        if (settings.bulkThreshold() > 0 && total >= settings.bulkThreshold()) {
            // A batch this size is a sweep, a command, or another mod filling the inventory. Saying
            // "+1300 new items" is noise, so it is dropped rather than shown.
            pending.clear();
            GatedJei.LOGGER.debug("Suppressed a comprehension notice for {} items (bulk threshold {}).",
                    total, settings.bulkThreshold());
            return null;
        }

        int cap = Math.max(1, settings.maxIcons());
        List<Item> icons = new ArrayList<>(Math.min(total, cap));
        for (Item item : pending) {
            if (icons.size() >= cap) {
                break;
            }
            icons.add(item);
        }
        pending.clear();
        lastEmitTick = tick;
        everEmitted = true;
        return new Notice(List.copyOf(icons), total);
    }
}
