package com.gatedjei.jei;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.discovery.SubtypeKeys;
import com.gatedjei.recipe.RecipeInputResolver;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.recipe.IRecipeManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bridge between discovery state and JEI's runtime.
 *
 * <p>========================= JEI API SURFACE USED (please verify) =========================
 * Confirmed against the JEI 1.21.x source (commit d4ea796e) for the runtime entry points:
 *   - IJeiRuntime#getRecipeManager(), #getIngredientManager()                      [confirmed]
 * The following recipe-manager calls are correct for JEI 19.x but were not line-checked for
 * your exact build, so verify the signatures against the jei-1.21.1-*-api jar you depend on:
 *   - IRecipeManager#createRecipeCategoryLookup().includeHidden().get()  -> Stream<IRecipeCategory<?>>
 *   - IRecipeCategory#getRecipeType()                                    -> RecipeType<T>
 *   - IRecipeManager#createRecipeLookup(RecipeType<T>).includeHidden().get() -> Stream<T>
 *   - IRecipeManager#hideRecipes(RecipeType<T>, Collection<T>)
 *   - IRecipeManager#unhideRecipes(RecipeType<T>, Collection<T>)
 *   - IIngredientManager#removeIngredientsAtRuntime(IIngredientType<V>, Collection<V>)
 *   - IIngredientManager#addIngredientsAtRuntime(IIngredientType<V>, Collection<V>)
 * If a name differs, this is the only file you should need to touch.
 *
 * NOTE: runtime hide/unhide is a real-JEI feature. EMI's JEI-compat layer (TooManyRecipeViewers)
 * deliberately throws IllegalStateException for runtime registry edits — so this mod is a no-op /
 * may warn under EMI. Use actual JEI.
 * ========================================================================================
 */
public final class RecipeGate {
    public static final RecipeGate INSTANCE = new RecipeGate();

    /** One gated recipe: its JEI type, the recipe object, and the item sets needed to reveal it. */
    private static final class Gated {
        final RecipeType<?> type;
        final Object recipe;
        final List<Set<Item>> inputGroups;
        final Set<Item> outputItems;
        final boolean resolved;
        boolean visible; // current JEI visibility we believe is applied

        Gated(RecipeType<?> type, Object recipe, RecipeInputResolver.Resolved r) {
            this.type = type;
            this.recipe = recipe;
            this.inputGroups = r != null ? r.inputGroups() : List.of();
            this.outputItems = r != null ? r.outputItems() : Set.of();
            this.resolved = r != null;
        }
    }

    private IJeiRuntime runtime;
    private final List<Gated> all = new ArrayList<>();
    private final Map<Item, List<Gated>> byItem = new HashMap<>(); // reverse index: item -> recipes that reference it
    // Snapshot of every JEI item-list variant grouped by Item, captured before anything is hidden.
    // Needed because items like enchanted books / potions appear as many NBT variants under one Item;
    // on discovery we must re-add ALL of them, not just a blank new ItemStack(item).
    private final Map<Item, List<ItemStack>> itemVariants = new HashMap<>();
    // For granular mode: subtype-variant key -> its JEI list stack, so we can re-add a single variant.
    private final Map<String, ItemStack> variantByKey = new HashMap<>();
    // Complete fluid list captured before any removal, so reset can re-hide reliably (same reason as items).
    private final List<FluidStack> fluidSnapshot = new ArrayList<>();

    private RecipeGate() {}

    // ---- lifecycle ----

    public synchronized void onRuntimeAvailable(IJeiRuntime runtime) {
        this.runtime = runtime;
        buildIndex();
        applyFull();
    }

    public synchronized void onRuntimeUnavailable() {
        this.runtime = null;
        all.clear();
        byItem.clear();
        itemVariants.clear();
        variantByKey.clear();
        fluidSnapshot.clear();
    }

    // ---- index ----

    private void buildIndex() {
        all.clear();
        byItem.clear();
        IRecipeManager rm = runtime.getRecipeManager();

        long start = System.currentTimeMillis();
        List<IRecipeCategory<?>> categories = rm.createRecipeCategoryLookup().includeHidden().get().toList();
        for (IRecipeCategory<?> category : categories) {
            indexCategory(rm, category);
        }
        if (Config.LOG_STATS.get()) {
            long resolved = all.stream().filter(g -> g.resolved).count();
            GatedJei.LOGGER.info("Indexed {} recipes ({} with readable inputs) across {} categories in {} ms.",
                    all.size(), resolved, categories.size(), System.currentTimeMillis() - start);
        }
    }

    private <T> void indexCategory(IRecipeManager rm, IRecipeCategory<T> category) {
        RecipeType<T> type = category.getRecipeType();
        List<T> recipes = rm.createRecipeLookup(type).includeHidden().get().toList();
        for (T recipe : recipes) {
            RecipeInputResolver.Resolved resolved = RecipeInputResolver.resolve(recipe).orElse(null);
            Gated g = new Gated(type, recipe, resolved);
            all.add(g);
            // index by every item that can satisfy any input group, plus outputs (for output-gating)
            for (Set<Item> group : g.inputGroups) {
                for (Item item : group) {
                    byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(g);
                }
            }
            for (Item item : g.outputItems) {
                byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(g);
            }
        }
    }

    // ---- gating logic ----

    private boolean isSatisfied(Gated g) {
        DiscoveryState state = DiscoveryState.get();
        // every input group needs at least one discovered item
        for (Set<Item> group : g.inputGroups) {
            boolean any = false;
            for (Item item : group) {
                if (state.isDiscovered(item)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        // optional: outputs must be discovered too
        if (Config.REQUIRE_OUTPUTS_DISCOVERED.get()) {
            for (Item item : g.outputItems) {
                if (!state.isDiscovered(item)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Should this recipe be visible right now, given config + discovery? */
    private boolean shouldBeVisible(Gated g) {
        if (Config.REVEAL_ALL.get()) {
            return true;
        }
        if (!g.resolved) {
            // can't read its inputs
            return Config.UNRESOLVED_POLICY.get() == Config.UnresolvedPolicy.REVEAL;
        }
        // A recipe with no input groups at all (e.g. nothing readable) is treated as unresolved-ish:
        // reveal it so we never permanently hide something we can't reason about.
        if (g.inputGroups.isEmpty() && g.outputItems.isEmpty()) {
            return true;
        }
        return isSatisfied(g);
    }

    // ---- apply ----

    /** Full pass: hide everything that should be hidden, unhide everything that should be visible. */
    private void applyFull() {
        if (runtime == null) {
            return;
        }
        IRecipeManager rm = runtime.getRecipeManager();

        // group recipes by type so we can batch hide/unhide
        Map<RecipeType<?>, List<Object>> toHide = new IdentityHashMap<>();
        Map<RecipeType<?>, List<Object>> toShow = new IdentityHashMap<>();
        Set<Item> itemsToHideFromList = new HashSet<>();

        for (Gated g : all) {
            boolean visible = shouldBeVisible(g);
            g.visible = visible;
            (visible ? toShow : toHide)
                    .computeIfAbsent(g.type, k -> new ArrayList<>())
                    .add(g.recipe);

            if (!visible && Config.HIDE_UNDISCOVERED_ITEMS.get()) {
                collectUndiscoveredOutputs(g, itemsToHideFromList);
            }
        }

        try {
            toHide.forEach((type, recipes) -> hide(rm, type, recipes));
            toShow.forEach((type, recipes) -> unhide(rm, type, recipes));
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("Failed to apply recipe gating (is a JEI compat layer like EMI/TMRV active?): {}",
                    t.toString());
        }

        if (Config.HIDE_UNDISCOVERED_ITEMS.get()) {
            applyItemListHiding();
        }

        if (Config.HIDE_UNDISCOVERED_FLUIDS.get()) {
            applyFluidListHiding();
        }

        if (Config.LOG_STATS.get()) {
            long shown = all.stream().filter(g -> g.visible).count();
            GatedJei.LOGGER.info("Gating applied: {} of {} recipes visible; {} items discovered.",
                    shown, all.size(), DiscoveryState.get().count());
        }
    }

    /** Incremental: only re-check recipes that reference one of the newly discovered items. */
    public synchronized void onNewlyDiscovered(Set<Item> newly) {
        if (runtime == null || Config.REVEAL_ALL.get()) {
            return;
        }

        // Reveal the discovered items in JEI's list FIRST, independent of recipes. Every discovered
        // item should appear — including ones that are in no recipe at all (trident, bottle o'
        // enchanting, etc.). This must happen before the no-candidates early return below.
        if (Config.HIDE_UNDISCOVERED_ITEMS.get()) {
            addItemsToList(newly);
        }

        IRecipeManager rm = runtime.getRecipeManager();

        // unique candidate recipes touched by the new items
        Set<Gated> candidates = new HashSet<>();
        for (Item item : newly) {
            List<Gated> list = byItem.get(item);
            if (list != null) {
                candidates.addAll(list);
            }
        }
        if (candidates.isEmpty()) {
            return; // no recipes to unhide; the item list was already updated above
        }

        Map<RecipeType<?>, List<Object>> toShow = new IdentityHashMap<>();
        for (Gated g : candidates) {
            if (g.visible) {
                continue; // already shown
            }
            if (shouldBeVisible(g)) {
                g.visible = true;
                toShow.computeIfAbsent(g.type, k -> new ArrayList<>()).add(g.recipe);
            }
        }

        try {
            toShow.forEach((type, recipes) -> unhide(rm, type, recipes));
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("Failed to unhide newly unlocked recipes: {}", t.toString());
        }
    }

    /** Re-apply from scratch (used by /gatedjei reveal & config changes). */
    public synchronized void reapply() {
        if (runtime != null) {
            applyFull();
        }
    }

    public synchronized int totalRecipes() {
        return all.size();
    }

    public synchronized long visibleRecipes() {
        return all.stream().filter(g -> g.visible).count();
    }

    // ---- typed JEI calls (the generics dance) ----

    @SuppressWarnings("unchecked")
    private <T> void hide(IRecipeManager rm, RecipeType<?> type, List<Object> recipes) {
        // recipes were obtained from createRecipeLookup(type), so they are genuinely T.
        rm.hideRecipes((RecipeType<T>) type, (List<T>) (List<?>) recipes);
    }

    @SuppressWarnings("unchecked")
    private <T> void unhide(IRecipeManager rm, RecipeType<?> type, List<Object> recipes) {
        rm.unhideRecipes((RecipeType<T>) type, (List<T>) (List<?>) recipes);
    }

    // ---- optional: hide items from JEI's ingredient list ----

    private void collectUndiscoveredOutputs(Gated g, Set<Item> out) {
        DiscoveryState state = DiscoveryState.get();
        for (Item item : g.outputItems) {
            if (!state.isDiscovered(item)) {
                out.add(item);
            }
        }
    }

    private void applyItemListHiding() {
        // Hide every item in the registry that hasn't been discovered.
        // TODO(verify): removeIngredientsAtRuntime signature on your JEI build.
        if (runtime == null) {
            return;
        }
        IIngredientManager im = runtime.getIngredientManager();
        DiscoveryState state = DiscoveryState.get();
        boolean granular = Config.GRANULAR_SUBTYPE_DISCOVERY.get();

        // Capture the complete variant map the first time (the list is still whole here, before any
        // removal). We hide from THIS snapshot, not from getAllIngredients() — items removed and then
        // re-added at runtime aren't reliably returned by getAllIngredients(), which is what left
        // previously-discovered items (esp. non-crafted ones) visible after /gatedjei reset.
        if (itemVariants.isEmpty()) {
            for (ItemStack stack : im.getAllIngredients(VanillaTypes.ITEM_STACK)) {
                itemVariants.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack);
                if (SubtypeKeys.isSubtypeVariant(stack)) {
                    variantByKey.putIfAbsent(SubtypeKeys.variantKey(stack), stack);
                }
            }
        }

        List<ItemStack> hide = new ArrayList<>();
        for (List<ItemStack> variants : itemVariants.values()) {
            for (ItemStack stack : variants) {
                boolean subtype = SubtypeKeys.isSubtypeVariant(stack);
                boolean discovered = (granular && subtype)
                        ? state.isVariantDiscovered(SubtypeKeys.variantKey(stack))
                        : state.isDiscovered(stack.getItem());
                if (!discovered) {
                    hide.add(stack);
                }
            }
        }
        if (!hide.isEmpty()) {
            try {
                im.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, hide);
            } catch (Throwable t) {
                GatedJei.LOGGER.warn("hideUndiscoveredItems failed: {}", t.toString());
            }
        }
    }

    private void addItemsToList(Set<Item> newly) {
        if (runtime == null) {
            return;
        }
        boolean granular = Config.GRANULAR_SUBTYPE_DISCOVERY.get();
        IIngredientManager im = runtime.getIngredientManager();
        List<ItemStack> add = new ArrayList<>();
        for (Item item : newly) {
            List<ItemStack> variants = itemVariants.get(item);
            if (variants == null || variants.isEmpty()) {
                add.add(new ItemStack(item)); // not in snapshot: fall back to base stack
                continue;
            }
            for (ItemStack v : variants) {
                // In granular mode, subtype variants (specific enchantments/potions) are revealed
                // one at a time via onNewlyDiscoveredVariants, not blanket-added with their item.
                if (granular && SubtypeKeys.isSubtypeVariant(v)) {
                    continue;
                }
                add.add(v);
            }
        }
        if (add.isEmpty()) {
            return;
        }
        try {
            // TODO(verify): addIngredientsAtRuntime signature on your JEI build.
            im.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, add);
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("re-adding discovered items to list failed: {}", t.toString());
        }
    }

    /** Granular mode: reveal individual subtype variants (one enchanted book / potion) as discovered. */
    public synchronized void onNewlyDiscoveredVariants(Set<String> newlyKeys) {
        if (runtime == null || Config.REVEAL_ALL.get()
                || !Config.HIDE_UNDISCOVERED_ITEMS.get()
                || !Config.GRANULAR_SUBTYPE_DISCOVERY.get()) {
            return;
        }
        IIngredientManager im = runtime.getIngredientManager();
        List<ItemStack> add = new ArrayList<>();
        for (String key : newlyKeys) {
            ItemStack v = variantByKey.get(key);
            if (v != null) {
                add.add(v);
            }
        }
        if (add.isEmpty()) {
            return;
        }
        try {
            im.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, add);
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("re-adding discovered variant to list failed: {}", t.toString());
        }
    }

    // ---- fluids: hide undiscovered fluids from JEI's fluid list ----

    /** Incremental: re-add fluids to JEI's list as they're discovered (wading / buckets). */
    public synchronized void onNewlyDiscoveredFluids(Set<Fluid> newly) {
        if (runtime == null || !Config.HIDE_UNDISCOVERED_FLUIDS.get()) {
            return;
        }
        IIngredientManager im = runtime.getIngredientManager();
        List<FluidStack> add = new ArrayList<>();
        for (Fluid fluid : newly) {
            // TODO(verify): FluidStack(Fluid, int) constructor on your build; if it needs a Holder,
            // use new FluidStack(fluid.builtInRegistryHolder(), FluidType.BUCKET_VOLUME).
            add.add(new FluidStack(fluid, FluidType.BUCKET_VOLUME));
        }
        try {
            im.addIngredientsAtRuntime(NeoForgeTypes.FLUID_STACK, add);
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("re-adding discovered fluids to list failed: {}", t.toString());
        }
    }

    private void applyFluidListHiding() {
        if (runtime == null) {
            return;
        }
        IIngredientManager im = runtime.getIngredientManager();
        DiscoveryState state = DiscoveryState.get();
        // Capture the full fluid list once, then hide from the snapshot (not getAllIngredients) so a
        // fluid that was discovered and re-added at runtime still gets re-hidden on reset.
        // TODO(verify): NeoForgeTypes.FLUID_STACK is JEI's NeoForge fluid ingredient type.
        if (fluidSnapshot.isEmpty()) {
            for (FluidStack stack : im.getAllIngredients(NeoForgeTypes.FLUID_STACK)) {
                fluidSnapshot.add(stack);
            }
        }
        List<FluidStack> hide = new ArrayList<>();
        for (FluidStack stack : fluidSnapshot) {
            if (!state.isDiscovered(stack.getFluid())) {
                hide.add(stack);
            }
        }
        if (!hide.isEmpty()) {
            try {
                im.removeIngredientsAtRuntime(NeoForgeTypes.FLUID_STACK, hide);
            } catch (Throwable t) {
                GatedJei.LOGGER.warn("hideUndiscoveredFluids failed: {}", t.toString());
            }
        }
    }
}
