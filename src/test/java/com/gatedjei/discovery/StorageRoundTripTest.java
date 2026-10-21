package com.gatedjei.discovery;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: a real NBT save/load round-trip through {@link DiscoveryStorage}, writing to the actual
 * game directory the test harness provides.
 */
class StorageRoundTripTest {

    private static final String KEY = "gatedjei_storage_roundtrip_test";

    @Test
    void unresolvableIdsAreWrittenBackVerbatim() {
        // The regression this guards: an id the current registry cannot resolve used to be dropped
        // on load, so the next save erased it. Uninstalling a mod, loading once and reinstalling it
        // would silently wipe every discovery that came from it.
        DiscoveryStorage.save(KEY,
                Set.of(Items.OAK_LOG),
                Set.of(Fluids.WATER),
                Set.of("minecraft:enchanted_book#ench:minecraft:sharpness=3"),
                Set.of("somemod:cobalt_ingot"),
                Set.of("somemod:molten_cobalt"));

        DiscoveryStorage.Loaded first = DiscoveryStorage.load(KEY);
        assertTrue(first.items().contains(Items.OAK_LOG), "resolved item survives");
        assertTrue(first.fluids().contains(Fluids.WATER), "resolved fluid survives");
        assertEquals(Set.of("minecraft:enchanted_book#ench:minecraft:sharpness=3"), first.variants());
        assertEquals(Set.of("somemod:cobalt_ingot"), first.unresolvedItemIds(),
                "the unknown item id is carried, not dropped");
        assertEquals(Set.of("somemod:molten_cobalt"), first.unresolvedFluidIds(),
                "the unknown fluid id is carried, not dropped");

        // Save what we loaded, exactly as the mod does on logout, then load again. The unknown ids
        // must still be there -- this is the round-trip that used to lose them.
        DiscoveryStorage.save(KEY, first.items(), first.fluids(), first.variants(),
                first.unresolvedItemIds(), first.unresolvedFluidIds());
        DiscoveryStorage.Loaded second = DiscoveryStorage.load(KEY);

        assertEquals(first.unresolvedItemIds(), second.unresolvedItemIds());
        assertEquals(first.unresolvedFluidIds(), second.unresolvedFluidIds());
        assertTrue(second.items().contains(Items.OAK_LOG));
        assertEquals(first.variants(), second.variants());
    }

    @Test
    void loadingAMissingFileYieldsEmptySetsRatherThanFailing() {
        DiscoveryStorage.Loaded loaded = DiscoveryStorage.load("gatedjei_definitely_no_such_key");
        assertTrue(loaded.items().isEmpty());
        assertTrue(loaded.fluids().isEmpty());
        assertTrue(loaded.variants().isEmpty());
        assertTrue(loaded.unresolvedItemIds().isEmpty());
        assertTrue(loaded.unresolvedFluidIds().isEmpty());
    }
}
