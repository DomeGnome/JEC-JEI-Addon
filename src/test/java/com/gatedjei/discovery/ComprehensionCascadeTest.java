package com.gatedjei.discovery;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: the comprehension cascade — what one held stack teaches beyond itself.
 *
 * <p>These exist because the cascade was silently not running under test. {@code ModConfigSpec}
 * throws until the config loads, and that throw landed after the item had been added but before the
 * base-container and fluid derivations, where a {@code catch (Throwable)} swallowed it. Item-level
 * assertions passed while the rest of comprehension did nothing. Config reads on this path are now
 * load-tolerant, and these tests pin the behaviour so it cannot regress quietly again.
 */
class ComprehensionCascadeTest {

    private record Result(Set<Item> items, Set<Fluid> fluids, Set<String> variants) {}

    private static Result comprehend(ItemStack stack) {
        Set<Item> items = new HashSet<>();
        Set<Fluid> fluids = new HashSet<>();
        Set<String> variants = new HashSet<>();
        ItemComprehension.comprehend(stack, items, fluids, variants);
        return new Result(items, fluids, variants);
    }

    @Test
    void aWaterBucketTeachesTheBucketAndTheWater() {
        // The headline cascade: one stack, three facts. If the config read throws and is swallowed,
        // only the first of these holds -- which is exactly the bug these tests were written for.
        var r = comprehend(new ItemStack(Items.WATER_BUCKET));
        assertTrue(r.items().contains(Items.WATER_BUCKET), "the item itself");
        assertTrue(r.items().contains(Items.BUCKET), "the emptied container it is built on");
        assertTrue(r.fluids().contains(Fluids.WATER), "the fluid it holds");
    }

    @Test
    void aLavaBucketTeachesLava() {
        var r = comprehend(new ItemStack(Items.LAVA_BUCKET));
        assertTrue(r.items().contains(Items.LAVA_BUCKET));
        assertTrue(r.items().contains(Items.BUCKET));
        assertTrue(r.fluids().contains(Fluids.LAVA));
    }

    @Test
    void aPotionTeachesTheGlassBottle() {
        var r = comprehend(new ItemStack(Items.POTION));
        assertTrue(r.items().contains(Items.POTION));
        assertTrue(r.items().contains(Items.GLASS_BOTTLE));
    }

    @Test
    void anEnchantedBookTeachesThePlainBook() {
        var r = comprehend(new ItemStack(Items.ENCHANTED_BOOK));
        assertTrue(r.items().contains(Items.ENCHANTED_BOOK));
        assertTrue(r.items().contains(Items.BOOK));
    }

    @Test
    void aTippedArrowTeachesThePlainArrow() {
        var r = comprehend(new ItemStack(Items.TIPPED_ARROW));
        assertTrue(r.items().contains(Items.ARROW));
    }

    @Test
    void stewTeachesTheBowl() {
        assertTrue(comprehend(new ItemStack(Items.MUSHROOM_STEW)).items().contains(Items.BOWL));
        assertTrue(comprehend(new ItemStack(Items.RABBIT_STEW)).items().contains(Items.BOWL));
        assertTrue(comprehend(new ItemStack(Items.BEETROOT_SOUP)).items().contains(Items.BOWL));
        assertTrue(comprehend(new ItemStack(Items.SUSPICIOUS_STEW)).items().contains(Items.BOWL));
    }

    @Test
    void aPlainItemTeachesOnlyItself() {
        var r = comprehend(new ItemStack(Items.STONE));
        assertTrue(r.items().contains(Items.STONE));
        assertTrue(r.items().size() == 1, "no spurious derivations, was " + r.items());
        assertTrue(r.fluids().isEmpty());
    }

    @Test
    void theWholeCascadeAlsoFlowsThroughTheObservationQueue() {
        // End to end: what a hook enqueues is what the discovery delta ends up containing.
        var queue = new ObservationQueue();
        queue.observe(new ItemStack(Items.WATER_BUCKET), ObservationQueue.Cause.EQUIPMENT);

        Set<Item> items = new HashSet<>();
        Set<Fluid> fluids = new HashSet<>();
        queue.drainTo((i, f, v) -> {
            items.addAll(i);
            fluids.addAll(f);
        });

        assertTrue(items.contains(Items.WATER_BUCKET));
        assertTrue(items.contains(Items.BUCKET));
        assertTrue(fluids.contains(Fluids.WATER),
                "filling your last bucket must still teach the fluid, via the equipment hook");
    }
}
