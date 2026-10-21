package com.gatedjei.game;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tier B: needs the real game registries bootstrapped. */
class VanillaItemTest {

    @Test
    void oakLogResolvesThroughTheItemRegistry() {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(Items.OAK_LOG);
        assertEquals("minecraft:oak_log", id.toString());
    }

    @Test
    void itemRegistryIdsAreUsableAsBitSetIndices() {
        int id = BuiltInRegistries.ITEM.getId(Items.OAK_LOG);
        assertTrue(id >= 0, "registry int id must be non-negative, got " + id);
        assertEquals(Items.OAK_LOG, BuiltInRegistries.ITEM.byId(id));
    }

    @Test
    void itemStackRoundTrips() {
        ItemStack stack = new ItemStack(Items.OAK_LOG, 7);
        assertFalse(stack.isEmpty());
        assertEquals(7, stack.getCount());
        assertEquals(Items.OAK_LOG, stack.getItem());
    }
}
