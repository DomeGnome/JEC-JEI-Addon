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

    public record Loaded(Set<Item> items, Set<Fluid> fluids, Set<String> variants) {}

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
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            return new Loaded(items, fluids, variants);
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

            ListTag itemList = root.getList(KEY_ITEMS, Tag.TAG_STRING);
            for (int i = 0; i < itemList.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(itemList.getString(i));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    items.add(BuiltInRegistries.ITEM.get(id));
                }
            }

            ListTag fluidList = root.getList(KEY_FLUIDS, Tag.TAG_STRING);
            for (int i = 0; i < fluidList.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(fluidList.getString(i));
                if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
                    fluids.add(BuiltInRegistries.FLUID.get(id));
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
        return new Loaded(items, fluids, variants);
    }

    public static void save(String key, Set<Item> items, Set<Fluid> fluids, Set<String> variants) {
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
            root.put(KEY_ITEMS, itemList);

            ListTag fluidList = new ListTag();
            for (Fluid fluid : fluids) {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id != null) {
                    fluidList.add(StringTag.valueOf(id.toString()));
                }
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
