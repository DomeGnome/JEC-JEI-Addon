package com.gatedjei.discovery;

import com.gatedjei.GatedJei;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists discovered items, fluids, and subtype-variant keys to {@code <gamedir>/gatedjei/<key>.dat}.
 * Items/fluids stored by registry id; variants stored as their {@link SubtypeKeys} strings.
 */
public final class DiscoveryStorage {
    private static final String FOLDER = "gatedjei";
    private static final String KEY_ITEMS = "discovered";
    private static final String KEY_FLUIDS = "discoveredFluids";
    private static final String KEY_VARIANTS = "discoveredVariants";

    /**
     * @param items              resolved item discoveries
     * @param fluids             resolved fluid discoveries
     * @param variants           subtype-variant keys (opaque strings, always round-trip)
     * @param unresolvedItemIds  item ids this launch cannot resolve, preserved verbatim
     * @param unresolvedFluidIds fluid ids this launch cannot resolve, preserved verbatim
     */
    public record Loaded(Set<Item> items, Set<Fluid> fluids, Set<String> variants,
                         Set<String> unresolvedItemIds, Set<String> unresolvedFluidIds) {}

    private DiscoveryStorage() {}

    private static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve(FOLDER);
    }

    private static Path fileFor(String key) {
        return folder().resolve(key + ".dat");
    }

    public static Loaded load(String key) {
        Set<Item> items = new HashSet<>();
        Set<Fluid> fluids = new HashSet<>();
        Set<String> variants = new HashSet<>();
        Set<String> unresolvedItems = new HashSet<>();
        Set<String> unresolvedFluids = new HashSet<>();
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            return new Loaded(items, fluids, variants, unresolvedItems, unresolvedFluids);
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

            // An id we cannot resolve is kept verbatim rather than dropped. Dropping it would mean
            // that uninstalling a mod, loading once, and reinstalling it silently wipes every
            // discovery that came from it -- and discovery is supposed to be permanent.
            ListTag itemList = root.getList(KEY_ITEMS, Tag.TAG_STRING);
            for (int i = 0; i < itemList.size(); i++) {
                String raw = itemList.getString(i);
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    items.add(BuiltInRegistries.ITEM.get(id));
                } else if (raw != null && !raw.isEmpty()) {
                    unresolvedItems.add(raw);
                }
            }

            ListTag fluidList = root.getList(KEY_FLUIDS, Tag.TAG_STRING);
            for (int i = 0; i < fluidList.size(); i++) {
                String raw = fluidList.getString(i);
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
                    fluids.add(BuiltInRegistries.FLUID.get(id));
                } else if (raw != null && !raw.isEmpty()) {
                    unresolvedFluids.add(raw);
                }
            }

            ListTag variantList = root.getList(KEY_VARIANTS, Tag.TAG_STRING);
            for (int i = 0; i < variantList.size(); i++) {
                String v = variantList.getString(i);
                if (v != null && !v.isEmpty()) {
                    variants.add(v);
                }
            }
        } catch (IOException | RuntimeException e) {
            GatedJei.LOGGER.warn("Failed to read discovery file {}: {}", file, e.toString());
        }
        return new Loaded(items, fluids, variants, unresolvedItems, unresolvedFluids);
    }

    public static void save(String key, Set<Item> items, Set<Fluid> fluids, Set<String> variants) {
        save(key, items, fluids, variants, Set.of(), Set.of());
    }

    public static void save(String key, Set<Item> items, Set<Fluid> fluids, Set<String> variants,
                            Set<String> unresolvedItemIds, Set<String> unresolvedFluidIds) {
        if (key == null) {
            return;
        }
        try {
            Files.createDirectories(folder());
            CompoundTag root = new CompoundTag();

            ListTag itemList = new ListTag();
            for (Item item : items) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    itemList.add(StringTag.valueOf(id.toString()));
                }
            }
            for (String raw : unresolvedItemIds) {
                itemList.add(StringTag.valueOf(raw)); // carried through from a previous launch
            }
            root.put(KEY_ITEMS, itemList);

            ListTag fluidList = new ListTag();
            for (Fluid fluid : fluids) {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id != null) {
                    fluidList.add(StringTag.valueOf(id.toString()));
                }
            }
            for (String raw : unresolvedFluidIds) {
                fluidList.add(StringTag.valueOf(raw)); // carried through from a previous launch
            }
            root.put(KEY_FLUIDS, fluidList);

            ListTag variantList = new ListTag();
            for (String v : variants) {
                variantList.add(StringTag.valueOf(v));
            }
            root.put(KEY_VARIANTS, variantList);

            NbtIo.writeCompressed(root, fileFor(key));
        } catch (IOException | RuntimeException e) {
            GatedJei.LOGGER.warn("Failed to write discovery file for key {}: {}", key, e.toString());
        }
    }
}
