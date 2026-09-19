package com.gatedjei.discovery;

import com.gatedjei.Config;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable, persistable keys that distinguish item *variants* the way JEI's list does — one per
 * enchanted-book enchantment, one per potion type, one per Iron's Spells 'n Spellbooks scroll
 * spell. Computed purely from data components, so the same key can be derived both from a held
 * stack (to record discovery) and from a JEI list entry (to decide visibility), with no JEI
 * dependency.
 *
 * <p>For an item with no subtype data the key is just its registry id, so non-subtype items behave
 * exactly as before (item-level).
 *
 * <p>TODO(verify): the component accessors below (STORED_ENCHANTMENTS / POTION_CONTENTS and their
 * methods) are the 1.21.1 forms; if a name differs on your build the compile error will point here.
 */
public final class SubtypeKeys {
    private SubtypeKeys() {}

    // --- Iron's Spells 'n Spellbooks soft-compat (spell scrolls) ---
    // Everything here is looked up by id at runtime, so there is NO hard dependency: if Iron's
    // isn't installed the data-component registry simply doesn't have this id, scrollSpells()
    // returns null, and every scroll branch below becomes a no-op. No build.gradle entry needed.
    //
    // Verified against Iron's `1.21` branch (MC 1.21.1): ComponentRegistry registers
    // SPELL_CONTAINER as "spell_container" with .persistent(SpellContainer.CODEC), and that codec
    // is registry-free — a spell serializes as a bare ResourceLocation string plus an int level —
    // so plain NbtOps is enough to read it back out.
    private static final ResourceLocation IRONS_SCROLL_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "scroll");
    private static final ResourceLocation IRONS_SPELL_CONTAINER_ID =
            ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "spell_container");
    /** Field names from SpellContainer.CODEC: the slot list, and a slot's spell id and level. */
    private static final String SPELL_DATA = "data";
    private static final String SPELL_ID = "id";
    private static final String SPELL_LEVEL = "level";

    /** True if this stack carries subtype data (a specific enchantment set, potion, or spell). */
    public static boolean isSubtypeVariant(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            return true;
        }
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        if (pc != null && (pc.potion().isPresent() || !pc.customEffects().isEmpty())) {
            return true;
        }
        // Must agree with variantKey() exactly, which is why both go through scrollSpells():
        // a stack that reports true here has to produce a key the JEI list entry produces too.
        return scrollSpells(stack) != null;
    }

    /**
     * A stable string identifying this exact variant (or just the item id if it has no subtype).
     * This is the <em>lookup</em> key: {@link com.gatedjei.jei.RecipeGate} derives it from JEI's
     * list entries, so it follows the current config.
     */
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

        List<String> spells = scrollSpells(stack);
        if (spells != null) {
            return base + spellKey(spells, scrollMode() == Config.SpellScrollDiscovery.PER_SPELL_AND_LEVEL);
        }

        return base;
    }

    /**
     * Every key worth <em>recording</em> for this stack. For a spell scroll that's both the
     * per-spell and the per-spell-and-level form, so flipping {@code ironsSpellScrollDiscovery}
     * on an existing save takes effect retroactively instead of orphaning the scrolls you had
     * already found — {@link DiscoveryStorage} persists these strings verbatim, and the form the
     * config isn't using is simply a string nothing ever looks up. Everything else contributes
     * its single {@link #variantKey}.
     */
    public static Set<String> variantKeys(ItemStack stack) {
        List<String> spells = scrollSpells(stack);
        if (spells == null) {
            return Set.of(variantKey(stack));
        }
        String base = baseId(stack);
        Set<String> keys = new LinkedHashSet<>(2);
        keys.add(base + spellKey(spells, true));
        keys.add(base + spellKey(spells, false));
        return keys;
    }

    private static String baseId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null ? id.toString() : "unknown";
    }

    // ---- Iron's spell scrolls ----

    private static Config.SpellScrollDiscovery scrollMode() {
        return Config.IRONS_SPELL_SCROLL_DISCOVERY.get();
    }

    /**
     * The spells on an Iron's scroll as sorted {@code "<spell id>@<level>"} strings, or null if
     * this isn't a readable scroll (wrong item, Iron's absent, scroll support switched off, empty
     * or unreadable container).
     *
     * <p>Only the spell id and level are read. {@code maxSpells}/{@code spellWheel}/
     * {@code mustEquip}/{@code improved}/{@code index}/{@code locked} are deliberately ignored:
     * they aren't what tells two scrolls apart, and including them could only make a held scroll
     * fail to match its JEI list entry — which would hide the scroll rather than reveal it.
     * For the same reason every failure here returns null, so the fallback is always "gate this
     * item the way we did before scroll support existed".
     */
    private static List<String> scrollSpells(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (scrollMode() == Config.SpellScrollDiscovery.OFF) {
            return null; // cheapest check first: this runs for every stack in JEI's list
        }
        if (!IRONS_SCROLL_ITEM_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return null;
        }
        try {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(IRONS_SPELL_CONTAINER_ID);
            if (type == null || !stack.has(type)) {
                return null; // Iron's not installed, or a scroll with no spell on it
            }
            if (!(encodeComponent(type, stack) instanceof CompoundTag container)) {
                return null;
            }
            return readSpellSlots(container);
        } catch (Throwable ignored) {
            // Soft-compat: if Iron's changes its component shape, fall back to item-level gating.
            return null;
        }
    }

    /**
     * Pulls the {@code "<spell id>@<level>"} strings out of a serialized SpellContainer, sorted so
     * the key is order-independent, or null if it holds no spells. Split out from
     * {@link #scrollSpells} so the field names can be exercised without a running game.
     */
    private static List<String> readSpellSlots(CompoundTag container) {
        ListTag slots = container.getList(SPELL_DATA, Tag.TAG_COMPOUND);
        List<String> spells = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            String id = slot.getString(SPELL_ID);
            if (id != null && !id.isEmpty()) {
                spells.add(id + "@" + slot.getInt(SPELL_LEVEL));
            }
        }
        if (spells.isEmpty()) {
            return null;
        }
        Collections.sort(spells);
        return spells;
    }

    /** {@code "#spell:<id>@<level>,..."}, or {@code "#spell:<id>,..."} when levels are ignored. */
    private static String spellKey(List<String> spells, boolean withLevel) {
        if (withLevel) {
            return "#spell:" + String.join(",", spells);
        }
        Set<String> ids = new TreeSet<>();
        for (String spell : spells) {
            int at = spell.lastIndexOf('@');
            ids.add(at >= 0 ? spell.substring(0, at) : spell);
        }
        return "#spell:" + String.join(",", ids);
    }

    /** Serializes a foreign component through its own codec; the {@code <T>} is capture plumbing. */
    private static <T> Tag encodeComponent(DataComponentType<T> type, ItemStack stack) {
        Codec<T> codec = type.codec();
        T value = stack.get(type);
        if (codec == null || value == null) {
            return null;
        }
        return codec.encodeStart(NbtOps.INSTANCE, value).result().orElse(null);
    }
}
