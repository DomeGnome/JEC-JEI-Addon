package com.gatedjei.discovery;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable, persistable keys that distinguish item *variants* the way JEI's list does — one per
 * enchanted-book enchantment, one per potion type, etc. Computed purely from vanilla components,
 * so the same key can be derived both from a held stack (to record discovery) and from a JEI list
 * entry (to decide visibility), with no JEI dependency.
 *
 * <p>For an item with no subtype data the key is just its registry id, so non-subtype items behave
 * exactly as before (item-level).
 *
 * <p>TODO(verify): the component accessors below (STORED_ENCHANTMENTS / POTION_CONTENTS and their
 * methods) are the 1.21.1 forms; if a name differs on your build the compile error will point here.
 */
public final class SubtypeKeys {
    private SubtypeKeys() {}

    /** True if this stack carries subtype data (a specific enchantment set or potion). */
    public static boolean isSubtypeVariant(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            return true;
        }
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        return pc != null && (pc.potion().isPresent() || !pc.customEffects().isEmpty());
    }

    /** A stable string identifying this exact variant (or just the item id if it has no subtype). */
    public static String variantKey(ItemStack stack) {
        String base = baseId(stack);
        if (stack == null || stack.isEmpty()) {
            return base;
        }

        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Holder<Enchantment> ench : stored.keySet()) {
                String id = ench.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
                parts.add(id + "=" + stored.getLevel(ench));
            }
            Collections.sort(parts);
            return base + "#ench:" + String.join(",", parts);
        }

        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        if (pc != null && (pc.potion().isPresent() || !pc.customEffects().isEmpty())) {
            String potionId = pc.potion().flatMap(Holder::unwrapKey)
                    .map(k -> k.location().toString()).orElse("custom");
            List<String> effects = new ArrayList<>();
            for (MobEffectInstance effect : pc.customEffects()) {
                String id = effect.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("?");
                effects.add(id + ":" + effect.getDuration() + ":" + effect.getAmplifier());
            }
            Collections.sort(effects);
            return base + "#potion:" + potionId + (effects.isEmpty() ? "" : "|" + String.join(",", effects));
        }

        return base;
    }

    private static String baseId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null ? id.toString() : "unknown";
    }
}
