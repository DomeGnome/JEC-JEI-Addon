package com.gatedjei.discovery;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The client-side discovery sets for the current save: items, fluids, and (for granular mode)
 * item variants identified by {@link SubtypeKeys} strings (per enchantment, per potion).
 * Listeners are notified with ONLY the delta so {@link com.gatedjei.jei.RecipeGate} can update
 * incrementally.
 */
public final class DiscoveryState {
    private static final DiscoveryState INSTANCE = new DiscoveryState();

    public static DiscoveryState get() {
        return INSTANCE;
    }

    private final Set<Item> discovered = new HashSet<>();
    private final Set<Fluid> discoveredFluids = new HashSet<>();
    private final Set<String> discoveredVariants = new HashSet<>();
    // Registry ids loaded from disk that this launch cannot resolve -- typically because a mod was
    // removed. Kept verbatim and written back on save so that removing a mod and later re-adding it
    // does not silently erase everything the player had discovered from it.
    private final Set<String> unresolvedItemIds = new HashSet<>();
    private final Set<String> unresolvedFluidIds = new HashSet<>();
    private final List<Consumer<Set<Item>>> listeners = new ArrayList<>();
    private final List<Consumer<Set<Fluid>>> fluidListeners = new ArrayList<>();
    private final List<Consumer<Set<String>>> variantListeners = new ArrayList<>();

    private String loadedKey = null;
    private boolean dirty = false;

    private DiscoveryState() {}

    // ---- listeners ----

    public void addListener(Consumer<Set<Item>> onNewlyDiscovered) {
        listeners.add(onNewlyDiscovered);
    }

    public void addFluidListener(Consumer<Set<Fluid>> onNewlyDiscovered) {
        fluidListeners.add(onNewlyDiscovered);
    }

    public void addVariantListener(Consumer<Set<String>> onNewlyDiscovered) {
        variantListeners.add(onNewlyDiscovered);
    }

    /**
     * Unregister a previously added item listener.
     *
     * <p>Note that a method reference creates a fresh object each time it is written, so callers
     * wanting to remove a listener must keep the instance they registered. Most callers want
     * {@link #clearListeners()} instead.
     */
    public void removeListener(Consumer<Set<Item>> listener) {
        listeners.remove(listener);
    }

    public void removeFluidListener(Consumer<Set<Fluid>> listener) {
        fluidListeners.remove(listener);
    }

    public void removeVariantListener(Consumer<Set<String>> listener) {
        variantListeners.remove(listener);
    }

    /**
     * Drop every registered listener.
     *
     * <p>Exists for test isolation: this is a process-wide singleton and Gradle reuses one JVM
     * across test methods, so a listener registered by one test would otherwise fire for every
     * later test in the run.
     *
     * <p>Deliberately NOT called from {@link #unload()}. Listeners are registered exactly once, in
     * the mod constructor, not per world load -- so clearing them on logout would permanently stop
     * JEI from being updated for the rest of the game session.
     */
    public void clearListeners() {
        listeners.clear();
        fluidListeners.clear();
        variantListeners.clear();
    }

    // ---- items ----

    public boolean isDiscovered(Item item) {
        return discovered.contains(item);
    }

    public Set<Item> view() {
        return Collections.unmodifiableSet(discovered);
    }

    public int count() {
        return discovered.size();
    }

    public void discoverAll(Iterable<Item> items) {
        Set<Item> newly = null;
        for (Item item : items) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            if (discovered.add(item)) {
                if (newly == null) {
                    newly = new HashSet<>();
                }
                newly.add(item);
            }
        }
        if (newly != null) {
            dirty = true;
            Set<Item> immutable = Collections.unmodifiableSet(newly);
            for (Consumer<Set<Item>> l : listeners) {
                l.accept(immutable);
            }
        }
    }

    public void discover(Item item) {
        discoverAll(Collections.singleton(item));
    }

    // ---- fluids ----

    public boolean isDiscovered(Fluid fluid) {
        return discoveredFluids.contains(fluid);
    }

    public Set<Fluid> viewFluids() {
        return Collections.unmodifiableSet(discoveredFluids);
    }

    public int fluidCount() {
        return discoveredFluids.size();
    }

    public void discoverAllFluids(Iterable<Fluid> fluids) {
        Set<Fluid> newly = null;
        for (Fluid fluid : fluids) {
            if (fluid == null || fluid == Fluids.EMPTY) {
                continue;
            }
            if (discoveredFluids.add(fluid)) {
                if (newly == null) {
                    newly = new HashSet<>();
                }
                newly.add(fluid);
            }
        }
        if (newly != null) {
            dirty = true;
            Set<Fluid> immutable = Collections.unmodifiableSet(newly);
            for (Consumer<Set<Fluid>> l : fluidListeners) {
                l.accept(immutable);
            }
        }
    }

    public void discoverFluid(Fluid fluid) {
        discoverAllFluids(Collections.singleton(fluid));
    }

    // ---- variants (granular subtype discovery: enchanted books, potions, ...) ----

    public boolean isVariantDiscovered(String key) {
        return discoveredVariants.contains(key);
    }

    public Set<String> viewVariants() {
        return Collections.unmodifiableSet(discoveredVariants);
    }

    public int variantCount() {
        return discoveredVariants.size();
    }

    public void discoverAllVariants(Iterable<String> keys) {
        Set<String> newly = null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (discoveredVariants.add(key)) {
                if (newly == null) {
                    newly = new HashSet<>();
                }
                newly.add(key);
            }
        }
        if (newly != null) {
            dirty = true;
            Set<String> immutable = Collections.unmodifiableSet(newly);
            for (Consumer<Set<String>> l : variantListeners) {
                l.accept(immutable);
            }
        }
    }

    // ---- ids this launch cannot resolve ----

    /** Item ids read from disk that are not in the current registry; preserved across saves. */
    public Set<String> viewUnresolvedItemIds() {
        return Collections.unmodifiableSet(unresolvedItemIds);
    }

    /** Fluid ids read from disk that are not in the current registry; preserved across saves. */
    public Set<String> viewUnresolvedFluidIds() {
        return Collections.unmodifiableSet(unresolvedFluidIds);
    }

    // ---- lifecycle ----

    public String loadedKey() {
        return loadedKey;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void replaceLoaded(String key, Set<Item> loadedItems, Set<Fluid> loadedFluids, Set<String> loadedVariants) {
        replaceLoaded(key, loadedItems, loadedFluids, loadedVariants, Set.of(), Set.of());
    }

    public void replaceLoaded(String key, Set<Item> loadedItems, Set<Fluid> loadedFluids,
                              Set<String> loadedVariants, Set<String> unresolvedItems,
                              Set<String> unresolvedFluids) {
        discovered.clear();
        discovered.addAll(loadedItems);
        discoveredFluids.clear();
        discoveredFluids.addAll(loadedFluids);
        discoveredVariants.clear();
        discoveredVariants.addAll(loadedVariants);
        unresolvedItemIds.clear();
        unresolvedItemIds.addAll(unresolvedItems);
        unresolvedFluidIds.clear();
        unresolvedFluidIds.addAll(unresolvedFluids);
        loadedKey = key;
        dirty = false;
    }

    /**
     * Forget the current save's discovery sets.
     *
     * <p>Listeners are intentionally left registered -- see {@link #clearListeners()}.
     */
    public void unload() {
        discovered.clear();
        discoveredFluids.clear();
        discoveredVariants.clear();
        unresolvedItemIds.clear();
        unresolvedFluidIds.clear();
        loadedKey = null;
        dirty = false;
    }
}
