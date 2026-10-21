package com.gatedjei.discovery;

import com.gatedjei.hook.DiscoveryHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: ordering around save boundaries.
 *
 * <p>The hazard being guarded: an observation applied <em>before</em> the discovery set for the
 * current save has loaded. {@code replaceLoaded} then wipes it, but {@code RecipeGate} has already
 * unhidden the recipe — leaving JEI showing a recipe whose discovery no longer exists. The handler
 * avoids that by refusing to drain while no save is loaded, and by clearing the queue whenever the
 * save key changes.
 *
 * <p>The handler's own tick method needs a running client, so these tests exercise the two
 * mechanisms it relies on rather than the event callback itself.
 */
class DrainOrderingTest {

    @BeforeEach
    @AfterEach
    void reset() {
        DiscoveryState.get().unload();
        DiscoveryState.get().clearListeners();
        DiscoveryHooks.queue().clear();
        DiscoveryHooks.resetLiveness();
    }

    /** Demonstrates the defect the guard exists to prevent. */
    @Test
    void applyingADeltaBeforeLoadWouldBeWipedByTheLoad() {
        var state = DiscoveryState.get();
        AtomicInteger notified = new AtomicInteger();
        state.addListener(delta -> notified.incrementAndGet());

        // Pretend we drained before the save was loaded.
        state.discoverAll(Set.of(Items.OAK_LOG));
        assertEquals(1, notified.get(), "the listener fired, so JEI would already have unhidden it");

        // The save then loads and replaces everything.
        state.replaceLoaded("some_save", Set.of(), Set.of(), Set.of());
        assertFalse(state.isDiscovered(Items.OAK_LOG),
                "the discovery is gone, but JEI was already told about it -- a stranded recipe");
    }

    @Test
    void aQueuedObservationSurvivesUntilDrained() {
        DiscoveryHooks.queue().observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        assertEquals(1, DiscoveryHooks.queue().pendingCount(),
                "holding the observation in the queue is what lets the handler defer it until loaded");
    }

    @Test
    void aSaveKeyChangeDropsPendingObservations() {
        // Observations belong to the save that was open when they happened. Carrying them across
        // would credit one world's discoveries to another.
        DiscoveryHooks.queue().observe(new ItemStack(Items.DIAMOND), ObservationQueue.Cause.ADD);
        assertEquals(1, DiscoveryHooks.queue().pendingCount());

        DiscoveryHooks.queue().clear(); // what ensureLoadedForCurrentSave does on a key change
        assertEquals(0, DiscoveryHooks.queue().pendingCount());

        var state = DiscoveryState.get();
        state.replaceLoaded("world_b", Set.of(), Set.of(), Set.of());
        assertFalse(state.isDiscovered(Items.DIAMOND),
                "the other save's pending observation must not land here");
    }

    @Test
    void drainingWithNothingQueuedIsANoOpNotAnException() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());
        AtomicInteger notified = new AtomicInteger();
        state.addListener(delta -> notified.incrementAndGet());

        for (int i = 0; i < 50; i++) {
            DiscoveryHooks.queue().drainTo((items, fluids, variants) -> {
                if (!items.isEmpty()) {
                    state.discoverAll(items);
                }
            });
        }
        assertEquals(0, notified.get(), "an empty queue must never produce a delta");
        assertFalse(state.isDirty());
    }

    @Test
    void aDrainAfterLoadAppliesNormally() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());

        DiscoveryHooks.queue().observe(new ItemStack(Items.OAK_LOG), ObservationQueue.Cause.SLOT);
        DiscoveryHooks.queue().drainTo((items, fluids, variants) -> state.discoverAll(items));

        assertTrue(state.isDiscovered(Items.OAK_LOG));
        assertTrue(state.isDirty(), "a real discovery marks the save dirty");
    }

    // ---- liveness ----

    @Test
    void aSweepDoesNotCountAsAHookFiring() {
        // Load-bearing: if sweeps marked liveness, the check could never detect dead mixins,
        // because the mod itself sweeps on every world join.
        DiscoveryHooks.resetLiveness();
        assertFalse(DiscoveryHooks.anyHookFired());

        DiscoveryHooks.onSweep(new ItemStack(Items.OAK_LOG));
        assertFalse(DiscoveryHooks.anyHookFired(),
                "a sweep must leave the liveness flag untouched");
    }

    @Test
    void aSweepStillEnqueuesTheStack() {
        DiscoveryHooks.queue().clear();
        DiscoveryHooks.onSweep(new ItemStack(Items.OAK_LOG));
        assertEquals(1, DiscoveryHooks.queue().pendingCount(),
                "not counting for liveness must not mean not observing");
    }
}
