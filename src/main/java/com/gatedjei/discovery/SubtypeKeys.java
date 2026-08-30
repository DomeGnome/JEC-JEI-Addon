package com.gatedjei.discovery;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable, persistable keys that distinguish item *variants* the way JEI's list does — one per
 * enchanted-book enchantment, one per potion type, etc. Computed purely from vanilla item data,
 * so the same key can be derived both from a held stack (to record discovery) and from a JEI list
 * entry (to decide visibility), with no JEI dependency.
 *
 * <p>For an item with no subtype data the key is just its registry id, so non-subtype items behave
 * exactly as before (item-level).
 *
 * <p><b>1.20.1 port.</b> Data Components only arrived in 1.20.5, so the two things the 1.21.1 build
 * read as components are read from NBT here instead:
 * <ul>
 *   <li>{@code DataComponents.STORED_ENCHANTMENTS} -&gt; the {@code StoredEnchantments} tag, via
 *       {@link EnchantedBookItem#getEnchantments(ItemStack)}. Deliberately NOT
 *       {@code EnchantmentHelper.getEnchantments}, which also reads the regular {@code Enchantments}
 *       tag — that would make every enchanted tool a "variant", which the 1.21.1 behaviour is not.</li>
 *   <li>{@code DataComponents.POTION_CONTENTS} -&gt; {@link PotionUtils#getPotion(ItemStack)} plus
 *       {@link PotionUtils#getCustomEffects(ItemStack)}.</li>
 * </ul>
 * The emitted key strings are identical in format to the 1.21.1 ones
 * ({@code <id>#ench:<id>=<lvl>,...} and {@code <id>#potion:<id>|<effect>:<dur>:<amp>,...}),
 * so saved discovery files stay compatible across the two versions.
 */
public final class SubtypeKeys {
    private SubtypeKeys() {}

    /** True if this stack carries subtype data (a specific enchantment set or potion). */
    public static boolean isSubtypeVariant(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!storedEnchantments(stack).isEmpty()) {
            return true;
        }
        return PotionUtils.getPotion(stack) != Potions.EMPTY || !PotionUtils.getCustomEffects(stack).isEmpty();
    }

    /** A stable string identifying this exact variant (or just the item id if it has no subtype). */
    public static String variantKey(ItemStack stack) {
        String base = baseId(stack);
        if (stack == null || stack.isEmpty()) {
            return base;
        }

        List<String> stored = storedEnchantments(stack);
        if (!stored.isEmpty()) {
            Collections.sort(stored);
            return base + "#ench:" + String.join(",", stored);
        }

        Potion potion = PotionUtils.getPotion(stack);
        List<MobEffectInstance> custom = PotionUtils.getCustomEffects(stack);
        if (potion != Potions.EMPTY || !custom.isEmpty()) {
            String potionId = "custom";
            if (potion != Potions.EMPTY) {
                ResourceLocation id = BuiltInRegistries.POTION.getKey(potion);
                potionId = id != null ? id.toString() : "custom";
            }
            List<String> effects = new ArrayList<>();
            for (MobEffectInstance effect : custom) {
                ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
                effects.add((id != null ? id.toString() : "?")
                        + ":" + effect.getDuration() + ":" + effect.getAmplifier());
            }
            Collections.sort(effects);
            return base + "#potion:" + potionId + (effects.isEmpty() ? "" : "|" + String.join(",", effects));
        }

        return base;
    }

    /**
     * The stack's stored enchantments as sorted-ready {@code "<registry id>=<level>"} parts.
     * Ids are round-tripped through the enchantment registry so an unregistered or malformed entry
     * is skipped, exactly like the Holder-based 1.21.1 read did.
     */
    private static List<String> storedEnchantments(ItemStack stack) {
        List<String> parts = new ArrayList<>();
        ListTag list;
        try {
            list = EnchantedBookItem.getEnchantments(stack);
        } catch (RuntimeException e) {
            return parts;
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
            if (id == null || !BuiltInRegistries.ENCHANTMENT.containsKey(id)) {
                continue;
            }
            parts.add(id + "=" + entry.getInt("lvl"));
        }
        return parts;
    }

    private static String baseId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null ? id.toString() : "unknown";
    }
}
