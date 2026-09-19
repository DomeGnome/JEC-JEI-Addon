package com.gatedjei.recipe;

import com.gatedjei.GatedJei;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parses the {@code extraCategoryCatalysts} config into "JEI recipe category -> the items any one
 * of which unlocks it".
 *
 * <p>This stands in for catalysts a category never registered. JEI hides a category while every
 * item it declared as a catalyst is hidden — that is what makes the smelting tab wait for a
 * furnace — but a category that declared <em>no</em> catalyst is never hidden that way and shows
 * from world load. Create's Sequenced Assembly is exactly that case: every recipe in it needs a
 * Deployer, and Create registers no catalyst for the category.
 *
 * <p>Entry syntax is {@code <category id>=<item id>[,<item id>...]}. "Any one of" matches JEI's own
 * catalyst rule, so a category behaves the same whether its catalysts came from its mod or here.
 *
 * <p>Nothing here is a hard dependency: ids are resolved against the live registries, so an entry
 * for a mod that isn't installed simply drops out.
 */
public final class CategoryCatalysts {
    private CategoryCatalysts() {}

    /** Config lines -> category id -> the items that unlock it. The form {@link com.gatedjei.jei.RecipeGate} wants. */
    public static Map<ResourceLocation, Set<Item>> resolve(List<? extends String> lines, boolean warn) {
        Map<ResourceLocation, Set<ResourceLocation>> parsed =
                parse(lines, BuiltInRegistries.ITEM::containsKey, warn);
        Map<ResourceLocation, Set<Item>> out = new LinkedHashMap<>(parsed.size());
        parsed.forEach((category, itemIds) -> {
            Set<Item> items = new LinkedHashSet<>(itemIds.size());
            for (ResourceLocation id : itemIds) {
                items.add(BuiltInRegistries.ITEM.get(id));
            }
            out.put(category, items);
        });
        return out;
    }

    /**
     * The parsing itself, kept free of the registries so it can be exercised without a running
     * game — {@code itemExists} is the only thing that needs one.
     *
     * <p>An entry whose items are all unknown is dropped rather than kept empty: an empty set
     * would satisfy nothing, which would hide that category forever instead of degrading to the
     * old behaviour. Unknown <em>category</em> ids can't be judged here (that needs JEI's runtime)
     * and are left for the caller to drop quietly.
     *
     * @param warn true to log skipped entries; callers that re-parse repeatedly pass false so a
     *             bad line doesn't spam the log on every pass.
     */
    static Map<ResourceLocation, Set<ResourceLocation>> parse(
            List<? extends String> lines, Predicate<ResourceLocation> itemExists, boolean warn) {
        Map<ResourceLocation, Set<ResourceLocation>> out = new LinkedHashMap<>();
        if (lines == null) {
            return out;
        }
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                warn(warn, "skipping '{}': expected <category id>=<item id>[,<item id>...]", line);
                continue;
            }
            String categoryPart = line.substring(0, eq).trim();
            ResourceLocation category = ResourceLocation.tryParse(categoryPart);
            if (category == null) {
                warn(warn, "skipping '{}': '{}' is not a valid category id", line, categoryPart);
                continue;
            }
            Set<ResourceLocation> items = new LinkedHashSet<>();
            for (String part : line.substring(eq + 1).split(",")) {
                String itemId = part.trim();
                if (itemId.isEmpty()) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(itemId);
                if (id == null || !itemExists.test(id)) {
                    // Normal when the mod isn't installed, so this is information, not a problem.
                    warn(warn, "ignoring unknown item '{}' in '{}'", itemId, line);
                    continue;
                }
                items.add(id);
            }
            if (items.isEmpty()) {
                warn(warn, "skipping '{}': none of its items exist", line);
                continue;
            }
            out.computeIfAbsent(category, k -> new LinkedHashSet<>()).addAll(items);
        }
        return out;
    }

    private static void warn(boolean enabled, String message, Object... args) {
        if (enabled) {
            GatedJei.LOGGER.warn("extraCategoryCatalysts: " + message, args);
        }
    }
}
