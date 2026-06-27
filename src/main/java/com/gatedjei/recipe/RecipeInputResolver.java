package com.gatedjei.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a JEI recipe object into the item sets we need for gating.
 *
 * <p><b>1.21.1 specific.</b> Here, recipe objects for the vanilla JEI categories are
 * {@link RecipeHolder}s wrapping a {@link Recipe}, and {@code Recipe#getIngredients()} returns
 * {@code NonNullList<Ingredient>} whose {@code Ingredient#getItems()} expands tags to concrete
 * {@code ItemStack[]}. This is exactly what we want, and it covers vanilla plus most modded
 * recipes that use the datapack recipe system.
 *
 * <p>TODO(1.21.2+): the ingredient/recipe API was reworked (holder-based ingredients, SlotDisplay),
 * and recipes moved server-side, so this resolver must be rewritten for those versions.
 *
 * <p>Recipe objects that are NOT vanilla-style (custom modded JEI categories) return
 * {@link Optional#empty()} and are handled by {@link com.gatedjei.Config.UnresolvedPolicy}.
 */
public final class RecipeInputResolver {

    /** One "any-of" group of items (e.g. all planks for a plank-tagged slot). */
    public record Resolved(List<Set<Item>> inputGroups, Set<Item> outputItems) {}

    private RecipeInputResolver() {}

    public static Optional<Resolved> resolve(Object recipeObject) {
        Recipe<?> recipe = asRecipe(recipeObject);
        if (recipe == null) {
            return Optional.empty();
        }

        List<Set<Item>> inputGroups = new ArrayList<>();
        try {
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            for (Ingredient ingredient : ingredients) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue; // empty slot = no requirement
                }
                Set<Item> group = new HashSet<>();
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack != null && !stack.isEmpty()) {
                        group.add(stack.getItem());
                    }
                }
                if (!group.isEmpty()) {
                    inputGroups.add(group);
                }
            }
        } catch (Throwable t) {
            // Some custom recipes implement Recipe but don't support getIngredients() — treat as unresolved.
            return Optional.empty();
        }

        Set<Item> outputs = new HashSet<>();
        try {
            ItemStack result = recipe.getResultItem(registryAccess());
            if (result != null && !result.isEmpty()) {
                outputs.add(result.getItem());
            }
        } catch (Throwable ignored) {
            // No reliable output (e.g. dynamic recipes); leave empty.
        }

        return Optional.of(new Resolved(inputGroups, outputs));
    }

    private static Recipe<?> asRecipe(Object obj) {
        if (obj instanceof RecipeHolder<?> holder) {
            return holder.value();
        }
        if (obj instanceof Recipe<?> r) {
            return r;
        }
        return null;
    }

    private static net.minecraft.core.RegistryAccess registryAccess() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.registryAccess();
        }
        if (mc.getConnection() != null) {
            return mc.getConnection().registryAccess();
        }
        throw new IllegalStateException("No registry access available");
    }
}
