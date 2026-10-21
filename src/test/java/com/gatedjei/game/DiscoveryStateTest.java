package com.gatedjei.game;

import com.gatedjei.discovery.DiscoveryState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B against the mod's OWN code: proves the test source set sees com.gatedjei.* and that
 * DiscoveryState's delta-notification contract holds. DiscoveryState is a singleton, so each
 * test brackets itself with unload().
 */
class DiscoveryStateTest {

    private DiscoveryState state;

    @BeforeEach
    void setUp() {
        state = DiscoveryState.get();
        state.unload();
    }

    @AfterEach
    void tearDown() {
        state.unload();
    }

    @Test
    void discoveringAnItemMakesItKnown() {
        assertFalse(state.isDiscovered(Items.OAK_LOG));
        state.discover(Items.OAK_LOG);
        assertTrue(state.isDiscovered(Items.OAK_LOG));
        assertEquals(1, state.count());
    }

    @Test
    void airIsNeverDiscovered() {
        state.discover(Items.AIR);
        assertFalse(state.isDiscovered(Items.AIR));
        assertEquals(0, state.count());
    }

    @Test
    void listenersSeeOnlyTheDelta() {
        List<Set<Item>> deltas = new ArrayList<>();
        state.addListener(deltas::add);

        state.discoverAll(List.of(Items.OAK_LOG, Items.STICK));
        state.discoverAll(List.of(Items.OAK_LOG, Items.DIRT)); // OAK_LOG already known

        assertEquals(2, deltas.size());
        assertEquals(Set.of(Items.OAK_LOG, Items.STICK), deltas.get(0));
        assertEquals(Set.of(Items.DIRT), deltas.get(1), "second delta must exclude the already-known OAK_LOG");
    }

    @Test
    void rediscoveringEmitsNoEventAndLeavesStateClean() {
        state.discover(Items.OAK_LOG);
        state.clearDirty();

        List<Set<Item>> deltas = new ArrayList<>();
        state.addListener(deltas::add);
        state.discover(Items.OAK_LOG);

        assertTrue(deltas.isEmpty(), "a repeat discovery must not notify listeners");
        assertFalse(state.isDirty(), "a repeat discovery must not dirty the save");
    }

    @Test
    void variantKeysAreTrackedSeparatelyFromItems() {
        state.discover(Items.ENCHANTED_BOOK);
        assertTrue(state.isDiscovered(Items.ENCHANTED_BOOK));
        assertFalse(state.isVariantDiscovered("minecraft:enchanted_book|minecraft:sharpness@3"),
                "knowing the base item must not imply knowing the variant");

        state.discoverAllVariants(List.of("minecraft:enchanted_book|minecraft:sharpness@3"));
        assertTrue(state.isVariantDiscovered("minecraft:enchanted_book|minecraft:sharpness@3"));
        assertEquals(1, state.variantCount());
    }
}
