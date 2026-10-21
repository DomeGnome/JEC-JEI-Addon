package com.gatedjei.discovery;

import com.gatedjei.GatedJei;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether an observed stack can be skipped without comprehending it.
 *
 * <h2>Why this is not a bitset of seen items</h2>
 *
 * <p>The obvious fast path — "have I seen this item id before?" — is wrong, because what a stack
 * teaches is a function of its <em>contents and the config</em>, not of its item identity. Keying
 * on identity permanently blinds the mod to:
 *
 * <ul>
 *   <li>a reusable fluid container, where one item id can hold any fluid (NeoForge keeps the
 *       contents in a data component): learned with water, it would never record lava;</li>
 *   <li>every enchanted book after the first, and every potion variant after the first.</li>
 * </ul>
 *
 * <p>So the question this class answers is <b>"has this stack nothing left to teach?"</b> — which
 * is the correct predicate, and still answers "yes" for the overwhelming majority of observations
 * (an already-known plain item), which is what keeps the hooks cheap.
 *
 * <p>A registry-id bitset was also considered and rejected on its own terms:
 * {@code BuiltInRegistries.ITEM} is a defaulted registry, so {@code getId} returns AIR's id 0 for
 * anything unknown — and since {@code ItemStack.EMPTY.getItem()} is AIR, the highest-frequency call
 * on the hooked path would poison index 0 and mark every client-only item as discovered forever.
 * Registry ids are also rebuilt mid-session on server join. And {@code getId} is itself a hash
 * lookup, so it is no cheaper than the {@code HashSet} it would replace.
 *
 * <p>Tables are built once per world join, since the item registry and capability registrations
 * can both differ between a singleplayer world and a server.
 */
public final class ComprehensionTables {

    /** Items whose stacks can carry subtype data we gate on (enchantments, potion contents). */
    private final Set<Item> variantCapable;
    /** Items that expose a fluid-handler capability, so a known item may still hold a new fluid. */
    private final Set<Item> fluidCapable;

    private ComprehensionTables(Set<Item> variantCapable, Set<Item> fluidCapable) {
        this.variantCapable = variantCapable;
        this.fluidCapable = fluidCapable;
    }

    /** An empty pair of tables: everything is treated as potentially teaching something. */
    public static ComprehensionTables empty() {
        return new ComprehensionTables(identitySet(), identitySet());
    }

    /**
     * Scan the item registry once and classify every item.
     *
     * <p>Roughly 1300 items in vanilla, of which a handful are variant-capable. Each probe is
     * wrapped: a modded item that throws while reporting its default components or capabilities
     * must not take down discovery, and the conservative outcome (absent from both tables) only
     * risks extra comprehension work, never a missed discovery.
     */
    public static ComprehensionTables build() {
        Set<Item> variant = identitySet();
        Set<Item> fluid = identitySet();

        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            try {
                DataComponentMap components = item.components();
                if (components.has(DataComponents.STORED_ENCHANTMENTS)
                        || components.has(DataComponents.POTION_CONTENTS)) {
                    variant.add(item);
                }
            } catch (Throwable ignored) {
                // Treated as not variant-capable; costs a comprehension, loses nothing.
            }
            try {
                if (new ItemStack(item).getCapability(Capabilities.FluidHandler.ITEM) != null) {
                    fluid.add(item);
                }
            } catch (Throwable ignored) {
                // Same: conservative in the safe direction.
            }
        }

        GatedJei.LOGGER.debug("Comprehension tables: {} variant-capable, {} fluid-capable items.",
                variant.size(), fluid.size());
        return new ComprehensionTables(variant, fluid);
    }

    /**
     * The hot-path question: can this observation be dropped without comprehending it?
     *
     * <p>Ordered cheapest-first. The common case — an already-known plain item — costs one
     * {@code isEmpty} check, one set lookup for discovery, and two identity-set lookups.
     *
     * @param isKnown whether the item itself has already been discovered
     * @return {@code true} if the stack has nothing left to teach and may be skipped
     */
    public boolean nothingLeftToTeach(ItemStack stack, Predicate<Item> isKnown) {
        if (stack == null || stack.isEmpty()) {
            return true; // an empty stack teaches nothing, and must never reach the discovery set
        }
        Item item = stack.getItem();
        if (!isKnown.test(item)) {
            return false; // a new item always needs comprehending
        }
        if (fluidCapable.contains(item)) {
            return false; // the same container may now hold a fluid we have not seen
        }
        if (variantCapable.contains(item) && !stack.isComponentsPatchEmpty()) {
            // isComponentsPatchEmpty() is a single field read. getComponentsPatch().isEmpty()
            // allocates and forces copy-on-write, which would be wrong on this path.
            return false; // a specific enchantment or potion we may not have recorded yet
        }
        return true;
    }

    public boolean isVariantCapable(Item item) {
        return variantCapable.contains(item);
    }

    public boolean isFluidCapable(Item item) {
        return fluidCapable.contains(item);
    }

    public int variantCapableCount() {
        return variantCapable.size();
    }

    public int fluidCapableCount() {
        return fluidCapable.size();
    }

    private static Set<Item> identitySet() {
        // Items are singletons, so identity comparison is both correct and the cheapest option.
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
