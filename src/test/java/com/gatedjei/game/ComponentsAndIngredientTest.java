package com.gatedjei.game;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B, harder: the pieces the discovery/gating design actually leans on --
 * DataComponents (the 1.21 replacement for NBT), Ingredient expansion, and tags.
 */
class ComponentsAndIngredientTest {

    @Test
    void dataComponentsAreRegistered() {
        ResourceLocation key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(DataComponents.DAMAGE);
        assertEquals("minecraft:damage", key.toString());
    }

    @Test
    void damageComponentDistinguishesTwoStacksOfTheSameItem() {
        ItemStack fresh = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
        worn.set(DataComponents.DAMAGE, 500);

        assertEquals(fresh.getItem(), worn.getItem());
        // Same item id, different component patch: this is exactly the "two-level check" case.
        assertNotEquals(fresh.getComponents(), worn.getComponents());
        assertFalse(ItemStack.isSameItemSameComponents(fresh, worn));
        assertTrue(ItemStack.isSameItem(fresh, worn));
    }

    @Test
    void enchantedBooksWithDifferentEnchantmentsAreDifferentSubtypes() {
        // Enchantments live in a datapack registry in 1.21, reachable here via a Holder lookup.
        ItemStack plain = new ItemStack(Items.ENCHANTED_BOOK);
        ItemStack sharpness = new ItemStack(Items.ENCHANTED_BOOK);

        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        Holder<Enchantment> sharp = lookupEnchantment();
        m.set(sharp, 3);
        sharpness.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());

        assertEquals(Items.ENCHANTED_BOOK, plain.getItem());
        assertEquals(Items.ENCHANTED_BOOK, sharpness.getItem());
        assertFalse(ItemStack.isSameItemSameComponents(plain, sharpness),
                "enchanted_book known != 'Sharpness III' known -- the item-id fast path alone is unsound here");
    }

    private static Holder<Enchantment> lookupEnchantment() {
        // VanillaRegistries-backed lookup; if this registry is not loaded the test will say so loudly.
        return net.minecraft.data.registries.VanillaRegistries.createLookup()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
    }

    @Test
    void ingredientExpandsToConcreteItems() {
        Ingredient planks = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        var ids = planks.getItems() == null ? java.util.List.<String>of()
                : java.util.Arrays.stream(planks.getItems())
                        .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
                        .sorted().toList();
        assertEquals(java.util.List.of("minecraft:birch_planks", "minecraft:oak_planks"), ids);
    }

    @Test
    void tagBackedIngredientIsReachable() {
        // Tags themselves need a loaded datapack; the TagKey object must at least resolve.
        assertEquals("minecraft:planks", ItemTags.PLANKS.location().toString());
    }
}
