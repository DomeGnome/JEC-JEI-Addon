package com.gatedjei.discovery;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: the fast path must mean "this stack has nothing left to teach", not "I have seen this
 * item id". Each test here is a case where the naive item-identity fast path would permanently
 * blind the mod.
 */
class ComprehensionTablesTest {

    private static ComprehensionTables tables;

    @BeforeAll
    static void buildTables() {
        tables = ComprehensionTables.build();
    }

    /** A discovery predicate over an explicit set of known items. */
    private static Predicate<Item> known(Item... items) {
        Set<Item> set = Set.of(items);
        return set::contains;
    }

    // ---- the common case: the fast path must actually be fast ----

    @Test
    void aKnownPlainItemIsSkipped() {
        var stack = new ItemStack(Items.STONE);
        assertTrue(tables.nothingLeftToTeach(stack, known(Items.STONE)),
                "a known, plain, unpatched item is the hot path and must short-circuit");
    }

    @Test
    void anUnknownItemIsNeverSkipped() {
        var stack = new ItemStack(Items.STONE);
        assertFalse(tables.nothingLeftToTeach(stack, known()),
                "an item we have never seen must always be comprehended");
    }

    // ---- empty stacks must never reach the discovery set ----

    @Test
    void emptyAndNullStacksTeachNothing() {
        assertTrue(tables.nothingLeftToTeach(ItemStack.EMPTY, known()));
        assertTrue(tables.nothingLeftToTeach(null, known()));

        var zero = new ItemStack(Items.OAK_LOG);
        zero.setCount(0);
        assertTrue(tables.nothingLeftToTeach(zero, known()),
                "a count-0 stack must be skipped, never recorded as AIR");
    }

    // ---- variants: the enchanted-book blindness ----

    @Test
    void enchantedBookIsClassifiedVariantCapable() {
        // Guards the detection mechanism itself: vanilla declares STORED_ENCHANTMENTS as a default
        // component on this item, which is what makes the probe work. If that ever changes, this
        // fails loudly rather than silently disabling variant gating.
        assertTrue(tables.isVariantCapable(Items.ENCHANTED_BOOK));
        assertTrue(tables.isVariantCapable(Items.POTION));
    }

    @Test
    void aKnownEnchantedBookDoesNotSkipADifferentEnchantment() {
        ItemStack sharpness = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        m.set(lookupSharpness(), 3);
        sharpness.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());

        // The item id is known, yet this specific variant may not be recorded -- so it must NOT skip.
        assertFalse(tables.nothingLeftToTeach(sharpness, known(Items.ENCHANTED_BOOK)),
                "a patched variant-capable stack must still be comprehended");
    }

    @Test
    void aKnownUnpatchedEnchantedBookStillSkips() {
        // The optimisation has to survive: a plain enchanted book with no component patch has
        // nothing new to say once the item is known.
        ItemStack plain = new ItemStack(Items.ENCHANTED_BOOK);
        assertTrue(plain.isComponentsPatchEmpty(), "precondition: no component patch");
        assertTrue(tables.nothingLeftToTeach(plain, known(Items.ENCHANTED_BOOK)));
    }

    // ---- fluids: the reusable-container blindness ----

    @Test
    void fluidContainersAreClassifiedFluidCapable() {
        assertTrue(tables.isFluidCapable(Items.WATER_BUCKET),
                "a bucket exposes the fluid-handler capability");
        assertFalse(tables.isFluidCapable(Items.STONE),
                "a plain block item does not");
    }

    @Test
    void aKnownFluidContainerIsNeverSkipped() {
        // This is the defect that matters most: one item id can hold different fluids (modded
        // reusable containers keep the contents in a data component). Skipping on item identity
        // would mean a container learned with water never records lava.
        var bucket = new ItemStack(Items.WATER_BUCKET);
        assertFalse(tables.nothingLeftToTeach(bucket, known(Items.WATER_BUCKET)),
                "a fluid-capable item must always be comprehended, even when the item is known");
    }

    // ---- the optimisation must still be worth having ----

    @Test
    void theVariantCapableSetIsSmall() {
        // If this ever ballooned, the fast path would stop being a fast path. Vanilla has a
        // handful; the bound is deliberately loose so modded registries do not fail the build.
        assertTrue(tables.variantCapableCount() > 0, "detection must find something");
        assertTrue(tables.variantCapableCount() < 100,
                "variant-capable items should be a small minority, was " + tables.variantCapableCount());
    }

    private static Holder<Enchantment> lookupSharpness() {
        return net.minecraft.data.registries.VanillaRegistries.createLookup()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
    }
}
