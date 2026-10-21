package com.gatedjei.discovery;


import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: the observation seam against real {@link ItemStack} mutation semantics.
 *
 * <p>Each test here corresponds to a defect found by reading the 1.21.1 source. They are the
 * reason the queue copies at enqueue and filters empties before copying, rather than after.
 */
class ObservationQueueTest {

    /** Captures what a drain produced, and how many times the sink was invoked. */
    private static final class Capture implements ObservationQueue.Sink {
        final List<Set<Item>> itemCalls = new ArrayList<>();
        int calls;

        @Override
        public void accept(Set<Item> items, Set<Fluid> fluids, Set<String> variants) {
            calls++;
            itemCalls.add(Set.copyOf(items));
        }

        Set<Item> onlyItems() {
            assertEquals(1, calls, "sink should have been called exactly once");
            return itemCalls.get(0);
        }
    }

    @Test
    void aStackMutatedAfterEnqueueStillDrainsToTheCorrectItem() {
        // This is the copyAndClear() hazard: Inventory#add zeroes the very stack a hook just saw.
        // Holding it by reference would drain as AIR and silently lose the pickup.
        var queue = new ObservationQueue();
        ItemStack live = new ItemStack(Items.DIAMOND_PICKAXE);

        queue.observe(live, ObservationQueue.Cause.ADD);
        live.setCount(0); // what copyAndClear() effectively does to the caller's stack

        assertTrue(live.isEmpty(), "precondition: the observed stack is now empty");

        var capture = new Capture();
        queue.drainTo(capture);
        assertTrue(capture.onlyItems().contains(Items.DIAMOND_PICKAXE),
                "the copy taken at enqueue must survive mutation of the original");
    }

    @Test
    void aCountZeroStackIsRejectedRatherThanRecordedAsAir() {
        // ItemStack.copy() returns EMPTY for an empty stack, so copying first would store AIR.
        // Rejecting before the copy is what keeps AIR out of the discovery set.
        var queue = new ObservationQueue();
        ItemStack zero = new ItemStack(Items.OAK_LOG);
        zero.setCount(0);
        assertTrue(zero.isEmpty(), "precondition: a count-0 stack reports empty");

        queue.observe(zero, ObservationQueue.Cause.SLOT);
        assertEquals(0, queue.pendingCount(), "a count-0 stack must not be queued at all");

        var capture = new Capture();
        assertEquals(0, queue.drainTo(capture), "nothing to drain");
        assertEquals(0, capture.calls, "an empty drain must not call the sink");
    }

    @Test
    void emptyAndNullStacksAreIgnored() {
        var queue = new ObservationQueue();
        queue.observe(ItemStack.EMPTY, ObservationQueue.Cause.SLOT);
        queue.observe(null, ObservationQueue.Cause.SLOT);
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void aBatchProducesExactlyOneSinkCall() {
        // 40 hook firings in one tick must collapse into a single discovery delta, so JEI is
        // updated once rather than forty times.
        var queue = new ObservationQueue();
        for (int i = 0; i < 40; i++) {
            queue.observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        }
        assertEquals(40, queue.pendingCount());

        var capture = new Capture();
        assertEquals(40, queue.drainTo(capture), "all 40 entries drained");
        assertEquals(1, capture.calls, "exactly one sink call for the whole batch");
        assertTrue(capture.onlyItems().contains(Items.OAK_LOG));
    }

    @Test
    void distinctItemsInOneBatchAllArrive() {
        var queue = new ObservationQueue();
        queue.observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        queue.observe(new ItemStack(Items.STICK), ObservationQueue.Cause.ADD);
        queue.observe(new ItemStack(Items.DIAMOND), ObservationQueue.Cause.CURSOR);

        var capture = new Capture();
        queue.drainTo(capture);
        var items = capture.onlyItems();
        assertTrue(items.containsAll(List.of(Items.OAK_LOG, Items.STICK, Items.DIAMOND)));
    }

    @Test
    void theQueueStopsGrowingAtItsBound() {
        var queue = new ObservationQueue(4);
        for (int i = 0; i < 50; i++) {
            queue.observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        }
        assertEquals(4, queue.pendingCount(), "the bound must cap pending growth");
    }

    @Test
    void aSinkThatThrowsDoesNotCorruptTheQueueState() {
        var queue = new ObservationQueue();
        queue.observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        // The drain polls everything before comprehending, so a throwing sink still leaves the
        // queue empty rather than replaying the same batch forever.
        try {
            queue.drainTo((i, f, v) -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // drainTo runs on our own tick handler, so a sink fault is the caller's to handle.
        }
        assertEquals(0, queue.pendingCount(), "entries must not be left pending after a drain");
    }

    @Test
    void clearDiscardsPendingObservations() {
        var queue = new ObservationQueue();
        queue.observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        queue.observe(new ItemStack(Items.STICK), ObservationQueue.Cause.SLOT);
        assertEquals(2, queue.pendingCount());

        queue.clear();
        assertEquals(0, queue.pendingCount(), "a save-key boundary must drop pending observations");

        var capture = new Capture();
        assertEquals(0, queue.drainTo(capture));
        assertEquals(0, capture.calls);
    }

    @Test
    void drainingAnEmptyQueueIsAFreeNoOp() {
        var queue = new ObservationQueue();
        var capture = new Capture();
        for (int i = 0; i < 100; i++) {
            assertEquals(0, queue.drainTo(capture));
        }
        assertEquals(0, capture.calls, "the idle path must never touch the sink");
        assertFalse(queue.pendingCount() > 0);
    }

    @Test
    void theEnqueuedCopyIsNotTheCallersInstance() {
        var queue = new ObservationQueue();
        ItemStack live = new ItemStack(Items.OAK_LOG);
        queue.observe(live, ObservationQueue.Cause.SLOT);

        List<ItemStack> queued = queue.snapshotPending();
        assertEquals(1, queued.size());
        assertFalse(queued.get(0) == live, "the queue must hold a copy, not the live stack");
        assertSame(Items.OAK_LOG, queued.get(0).getItem());
    }
}
