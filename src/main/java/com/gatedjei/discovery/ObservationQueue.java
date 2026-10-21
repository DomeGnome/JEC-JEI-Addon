package com.gatedjei.discovery;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single seam between "something entered the inventory" and "work out what it teaches".
 *
 * <p>Every hook funnels here. Nothing is comprehended inline: hooks enqueue and return, and the
 * queue is drained once per client tick. Three reasons, all of them load-bearing:
 *
 * <ul>
 *   <li><b>Thread safety.</b> In singleplayer the same inventory write paths execute on the
 *       integrated server thread as well as the client thread. Hooks guard on player identity, but
 *       enqueueing keeps all discovery-state mutation on the single draining thread regardless.</li>
 *   <li><b>Reentrancy.</b> Comprehension ends in JEI runtime calls. Making those from inside a
 *       packet handler or mid-inventory-write is asking for trouble.</li>
 *   <li><b>Batching.</b> Picking up a stack that lands across several slots fires several hooks;
 *       draining collapses them into one discovery delta, so JEI is updated once rather than
 *       once per slot.</li>
 * </ul>
 *
 * <h2>Why entries are copied at enqueue</h2>
 *
 * <p>{@code ItemStack} is mutable and the game reuses instances aggressively. {@code Inventory#add}
 * calls {@code stack.copyAndClear()}, which zeroes the very object a hook just saw — so an entry
 * held by reference would read as {@code AIR} by the time the queue drained, silently losing the
 * observation. Entries are therefore defensive copies.
 *
 * <p>{@code ItemStack#copy()} returns {@code EMPTY} for an already-empty stack, which would destroy
 * the item's identity. Empty stacks are therefore rejected <em>before</em> copying, never after.
 */
public final class ObservationQueue {

    /** Where an observation came from. Carried for diagnostics; the rule does not branch on it. */
    public enum Cause {
        /** A slot write: container sync, chest, trade, furnace output, command. */
        SLOT,
        /** {@code Inventory#add}: ground pickup, crafting remainder, hotbar-swap overflow. */
        ADD,
        /** The cursor / mouse-held stack. */
        CURSOR,
        /** A bulk container sync, including the inventory sent on world join. */
        SYNC,
        /** Creative middle-click pick-block. */
        PICK_BLOCK,
        /** An equipment-slot write: filling your last bucket, milking, offhand swap. */
        EQUIPMENT,
        /** Sneak-clicking a filled item frame. */
        FRAME,
        /** A reconciliation sweep over the whole inventory. */
        SWEEP
    }

    /**
     * Upper bound on pending entries.
     *
     * <p>The queue drains every client tick, so reaching this means draining has stopped entirely.
     * It exists to stop unbounded growth in that case, not as a routine limit — normal play never
     * approaches it.
     */
    static final int MAX_PENDING = 8192;

    private final Queue<ItemStack> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final int bound;

    private boolean warnedOverflow;
    private boolean warnedObserveFailure;
    private boolean warnedComprehendFailure;

    public ObservationQueue() {
        this(MAX_PENDING);
    }

    /** Visible for testing with a small bound. */
    ObservationQueue(int bound) {
        this.bound = bound;
    }

    /** What a drain hands back: the union of everything the batch taught. */
    public interface Sink {
        void accept(Set<Item> items, Set<Fluid> fluids, Set<String> variants);
    }

    // ---- enqueue ----

    /**
     * Record a stack to be comprehended on the next drain.
     *
     * <p><b>Never throws.</b> This runs inside vanilla inventory code: {@code Inventory#add} wraps
     * its body and rethrows as a crash report, and an exception during container-slot handling can
     * desync the connection. Any failure here is swallowed and warned about once.
     */
    public void observe(ItemStack stack, Cause cause) {
        try {
            // Reject empties BEFORE copying -- copy() would turn them into EMPTY and lose identity.
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (pendingCount.get() >= bound) {
                if (!warnedOverflow) {
                    warnedOverflow = true;
                    GatedJei.LOGGER.warn(
                            "Observation queue hit {} pending entries and is dropping observations. "
                                    + "This means the per-tick drain has stopped running.", bound);
                }
                return;
            }
            pending.add(stack.copy());
            pendingCount.incrementAndGet();
        } catch (Throwable t) {
            if (!warnedObserveFailure) {
                warnedObserveFailure = true;
                GatedJei.LOGGER.warn("Failed to record an observation ({}): {}", cause, t.toString());
            }
        }
    }

    // ---- drain ----

    /**
     * Comprehend everything pending and hand the union to {@code sink} in a single call.
     *
     * @return the number of entries drained (0 if there was nothing to do)
     */
    public int drainTo(Sink sink) {
        if (pending.isEmpty()) {
            return 0; // the idle path: one check per tick and nothing else
        }

        List<ItemStack> batch = new ArrayList<>();
        ItemStack s;
        while ((s = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            batch.add(s);
        }
        if (batch.isEmpty()) {
            return 0;
        }

        Set<Item> items = new HashSet<>();
        Set<Fluid> fluids = new HashSet<>();
        Set<String> variants = new HashSet<>();

        boolean inspectFluids = Config.orDefault(Config.DISCOVER_FLUIDS_BY_BUCKET, true);
        for (ItemStack stack : batch) {
            try {
                if (inspectFluids) {
                    ItemComprehension.comprehend(stack, items, fluids, variants);
                } else {
                    ItemComprehension.comprehendItemOnly(stack, items, variants);
                }
            } catch (Throwable t) {
                // One malformed stack must not cost us the rest of the batch.
                if (!warnedComprehendFailure) {
                    warnedComprehendFailure = true;
                    GatedJei.LOGGER.warn("Failed to comprehend an observed stack: {}", t.toString());
                }
            }
        }

        // One call, whatever the batch size -- so JEI sees a single delta.
        sink.accept(items, fluids, variants);
        return batch.size();
    }

    /** Discard everything pending. Used at save-key and session boundaries. */
    public void clear() {
        pending.clear();
        pendingCount.set(0);
    }

    public int pendingCount() {
        return pendingCount.get();
    }

    /** Visible for testing: the raw copies currently queued, without comprehending them. */
    List<ItemStack> snapshotPending() {
        return new ArrayList<>(new ArrayDeque<>(pending));
    }
}
