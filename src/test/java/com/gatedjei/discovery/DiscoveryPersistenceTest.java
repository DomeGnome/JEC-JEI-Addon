package com.gatedjei.discovery;

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
 * Tier B: persistence semantics and listener lifecycle on the real {@link DiscoveryState} singleton.
 *
 * <p>{@code DiscoveryState} is process-wide and Gradle reuses one JVM across test methods, so every
 * test here brackets with {@code unload()} and {@code clearListeners()}.
 */
class DiscoveryPersistenceTest {

    @BeforeEach
    @AfterEach
    void reset() {
        DiscoveryState.get().unload();
        DiscoveryState.get().clearListeners();
    }

    // ---- ids the current registry cannot resolve ----

    @Test
    void unresolvableIdsSurviveALoadAndAreOfferedBackForSaving() {
        // Simulates a save written while a mod was installed, then loaded without it. The ids must
        // be carried, not dropped: discovery is permanent, and reinstalling the mod should restore
        // what the player had already learned from it.
        var state = DiscoveryState.get();
        state.replaceLoaded("test_key",
                Set.of(Items.OAK_LOG),
                Set.of(),
                Set.of(),
                Set.of("somemod:cobalt_ingot", "somemod:ruby"),
                Set.of("somemod:molten_cobalt"));

        assertTrue(state.isDiscovered(Items.OAK_LOG), "resolved items still load normally");
        assertEquals(Set.of("somemod:cobalt_ingot", "somemod:ruby"), state.viewUnresolvedItemIds());
        assertEquals(Set.of("somemod:molten_cobalt"), state.viewUnresolvedFluidIds());
    }

    @Test
    void unloadDropsUnresolvedIdsAlongWithEverythingElse() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(Items.OAK_LOG), Set.of(), Set.of(),
                Set.of("somemod:thing"), Set.of());
        state.unload();
        assertTrue(state.viewUnresolvedItemIds().isEmpty());
        assertFalse(state.isDiscovered(Items.OAK_LOG));
    }

    @Test
    void theFourArgReplaceLoadedStillWorksAndClearsUnresolved() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of(), Set.of("somemod:x"), Set.of());
        assertFalse(state.viewUnresolvedItemIds().isEmpty());

        // The convenience overload is what /gatedjei reset uses: it must wipe everything.
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());
        assertTrue(state.viewUnresolvedItemIds().isEmpty(),
                "a reset must not resurrect ids from a previous load");
    }

    // ---- listener lifecycle ----

    @Test
    void clearListenersStopsFurtherNotifications() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());

        AtomicInteger calls = new AtomicInteger();
        state.addListener(delta -> calls.incrementAndGet());

        state.discover(Items.OAK_LOG);
        assertEquals(1, calls.get(), "a registered listener sees the delta");

        state.clearListeners();
        state.discover(Items.STICK);
        assertEquals(1, calls.get(), "after clearListeners no further notifications arrive");
    }

    @Test
    void removeListenerUnregistersTheSameInstance() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());

        AtomicInteger calls = new AtomicInteger();
        java.util.function.Consumer<Set<net.minecraft.world.item.Item>> listener =
                delta -> calls.incrementAndGet();
        state.addListener(listener);
        state.discover(Items.OAK_LOG);
        assertEquals(1, calls.get());

        state.removeListener(listener);
        state.discover(Items.STICK);
        assertEquals(1, calls.get());
    }

    @Test
    void unloadDoesNotUnregisterListeners() {
        // Load-bearing: listeners are registered once in the mod constructor, never per world.
        // If unload() dropped them, JEI would stop updating after the first world exit.
        var state = DiscoveryState.get();
        AtomicInteger calls = new AtomicInteger();
        state.addListener(delta -> calls.incrementAndGet());

        state.replaceLoaded("world_a", Set.of(), Set.of(), Set.of());
        state.discover(Items.OAK_LOG);
        assertEquals(1, calls.get());

        state.unload(); // leaving the world
        state.replaceLoaded("world_b", Set.of(), Set.of(), Set.of()); // joining another
        state.discover(Items.STICK);
        assertEquals(2, calls.get(),
                "the listener must still be live after a world change");
    }

    // ---- the delta contract the incremental JEI update depends on ----

    @Test
    void rediscoveringEmitsNothingAndLeavesStateClean() {
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(Items.OAK_LOG), Set.of(), Set.of());

        AtomicInteger calls = new AtomicInteger();
        state.addListener(delta -> calls.incrementAndGet());

        state.discover(Items.OAK_LOG); // already known
        assertEquals(0, calls.get(), "no delta means no listener call and no JEI churn");
        assertFalse(state.isDirty(), "and nothing to persist");
    }
}
