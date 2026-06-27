package com.gatedjei.discovery;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives discovery on the client:
 * <ul>
 *   <li>On login: load the discovery sets for the current save key.</li>
 *   <li>Every {@code scanIntervalTicks}: scan inventory for held items; scan held buckets and the
 *       fluid the player is standing in for fluids.</li>
 *   <li>Periodically + on logout: persist the sets.</li>
 * </ul>
 *
 * <p>We scan instead of listening to pickup events on purpose — pickup events fire server-side,
 * but scanning works the same in singleplayer and on dedicated servers from the client's view.
 */
@EventBusSubscriber(modid = GatedJei.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientDiscoveryHandler {
    private static int tickCounter = 0;
    private static int saveCounter = 0;
    private static final int SAVE_EVERY_TICKS = 600; // ~30s

    private ClientDiscoveryHandler() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ensureLoadedForCurrentSave();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DiscoveryState state = DiscoveryState.get();
        if (state.loadedKey() != null) {
            DiscoveryStorage.save(state.loadedKey(), state.view(), state.viewFluids(), state.viewVariants());
        }
        state.unload();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        DiscoveryState state = DiscoveryState.get();
        ensureLoadedForCurrentSave();

        if (++tickCounter < Config.SCAN_INTERVAL_TICKS.get()) {
            maybeSave(state);
            return;
        }
        tickCounter = 0;

        List<ItemStack> held = collectHeldStacks(player);

        Set<Item> items = new HashSet<>();
        Set<Fluid> fluids = new HashSet<>();
        Set<String> variants = new HashSet<>();
        boolean byBucket = Config.DISCOVER_FLUIDS_BY_BUCKET.get();
        for (ItemStack s : held) {
            if (byBucket) {
                // item + variant key + any contained fluid + emptied container
                ItemComprehension.comprehend(s, items, fluids, variants);
            } else {
                ItemComprehension.comprehendItemOnly(s, items, variants);
            }
        }
        if (Config.DISCOVER_FLUIDS_BY_WADING.get()) {
            collectFluidsFromSurroundings(player, fluids);
        }
        if (!items.isEmpty()) {
            state.discoverAll(items);
        }
        if (!fluids.isEmpty()) {
            state.discoverAllFluids(fluids);
        }
        if (!variants.isEmpty()) {
            state.discoverAllVariants(variants);
        }

        maybeSave(state);
    }

    private static void maybeSave(DiscoveryState state) {
        if (++saveCounter >= SAVE_EVERY_TICKS) {
            saveCounter = 0;
            if (state.isDirty() && state.loadedKey() != null) {
                DiscoveryStorage.save(state.loadedKey(), state.view(), state.viewFluids(), state.viewVariants());
                state.clearDirty();
            }
        }
    }

    /** Loads the discovery sets if they haven't been loaded for the current save key yet. */
    public static void ensureLoadedForCurrentSave() {
        DiscoveryState state = DiscoveryState.get();
        String key = SaveContext.currentKey();
        if (key.equals(state.loadedKey())) {
            return;
        }
        DiscoveryStorage.Loaded loaded = DiscoveryStorage.load(key);
        state.replaceLoaded(key, loaded.items(), loaded.fluids(), loaded.variants());
        GatedJei.LOGGER.info("Loaded {} items and {} fluids for save '{}'.",
                loaded.items().size(), loaded.fluids().size(), key);
    }

    // ---- collection helpers ----

    /** Everything the player is currently "holding": inventory, armor, offhand, and the cursor. */
    private static List<ItemStack> collectHeldStacks(LocalPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        out.addAll(inv.items);
        out.addAll(inv.armor);
        out.addAll(inv.offhand);
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        if (carried != null) {
            out.add(carried);
        }
        return out;
    }

    /** Discovers whatever fluid the player is standing in / submerged in. */
    private static void collectFluidsFromSurroundings(LocalPlayer player, Set<Fluid> out) {
        Level level = player.level();
        addFluidAt(level, player.blockPosition(), out);
        addFluidAt(level, BlockPos.containing(player.getEyePosition()), out);
        // Reliable vanilla fallbacks (these handle edge cases the position sample can miss).
        if (player.isInWater()) {
            out.add(Fluids.WATER);
        }
        if (player.isInLava()) {
            out.add(Fluids.LAVA);
        }
    }

    private static void addFluidAt(Level level, BlockPos pos, Set<Fluid> out) {
        add(out, level.getFluidState(pos).getType());
    }

    /** Adds a fluid, mapping a flowing variant to its still/source form (which is what JEI lists). */
    private static void add(Set<Fluid> out, Fluid fluid) {
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
