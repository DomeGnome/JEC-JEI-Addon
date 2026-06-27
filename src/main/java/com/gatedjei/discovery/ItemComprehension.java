package com.gatedjei.discovery;

import com.gatedjei.Config;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

import java.util.Set;

/**
 * Turns one {@link ItemStack} into everything it "teaches":
 * <ul>
 *   <li>the item itself;</li>
 *   <li>its subtype-variant key, if any (enchanted book / potion) — used in granular mode;</li>
 *   <li>the plain book, if it's an enchanted book;</li>
 *   <li>any fluid it contains and the emptied container (water bucket -> water + bucket).</li>
 * </ul>
 * Variant keys are always emitted (cheap); the caller decides whether to act on them based on the
 * granular-discovery config, so switching the toggle on later still reflects what you've handled.
 */
public final class ItemComprehension {
    private ItemComprehension() {}

    /** Item + variant key (+ plain book), but no fluid inspection. */
    public static void comprehendItemOnly(ItemStack stack, Set<Item> outItems, Set<String> outVariants) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        addItemAndVariant(stack, outItems, outVariants);
    }

    /** Full comprehension: item, variant key, plain book, contained fluid, and emptied container. */
    public static void comprehend(ItemStack stack, Set<Item> outItems, Set<Fluid> outFluids, Set<String> outVariants) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        addItemAndVariant(stack, outItems, outVariants);

        IFluidHandlerItem handler;
        ItemStack copy = stack.copy();
        try {
            handler = copy.getCapability(Capabilities.FluidHandler.ITEM);
        } catch (Throwable t) {
            handler = null;
        }
        if (handler == null) {
            return;
        }

        boolean hadFluid = false;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack fs = handler.getFluidInTank(tank);
            if (fs != null && !fs.isEmpty()) {
                addFluid(outFluids, fs.getFluid());
                hadFluid = true;
            }
        }
        if (!hadFluid) {
            return;
        }
        if (!Config.DISCOVER_BASE_CONTAINER.get()) {
            return; // base-container discovery toggled off: learn the fluid but not the empty container
        }
        try {
            handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            ItemStack emptied = handler.getContainer();
            if (emptied != null && !emptied.isEmpty()
                    && emptied.getItem() != Items.AIR
                    && emptied.getItem() != stack.getItem()) {
                outItems.add(emptied.getItem());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addItemAndVariant(ItemStack stack, Set<Item> outItems, Set<String> outVariants) {
        Item item = stack.getItem();
        if (item != Items.AIR) {
            outItems.add(item);
        }
        if (SubtypeKeys.isSubtypeVariant(stack)) {
            outVariants.add(SubtypeKeys.variantKey(stack));
        }
        // Holding a filled subtype item means you also hold the empty base it's built on.
        if (Config.DISCOVER_BASE_CONTAINER.get()) {
            Item base = baseContainerOf(item);
            if (base != null) {
                outItems.add(base);
            }
        }
    }

    /** The empty/base item a non-fluid subtype item is built on (book, bottle, arrow, bowl), or null. */
    private static Item baseContainerOf(Item item) {
        if (item == Items.ENCHANTED_BOOK) {
            return Items.BOOK;
        }
        if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
            return Items.GLASS_BOTTLE;
        }
        if (item == Items.TIPPED_ARROW) {
            return Items.ARROW;
        }
        if (item == Items.MUSHROOM_STEW || item == Items.RABBIT_STEW
                || item == Items.BEETROOT_SOUP || item == Items.SUSPICIOUS_STEW) {
            return Items.BOWL;
        }
        return null;
    }

    private static void addFluid(Set<Fluid> out, Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return;
        }
        if (fluid instanceof FlowingFluid flowing) {
            fluid = flowing.getSource();
        }
        if (fluid != Fluids.EMPTY) {
            out.add(fluid);
        }
    }
}
