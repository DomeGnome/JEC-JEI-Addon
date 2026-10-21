package com.gatedjei.discovery;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.hook.DiscoveryHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives discovery on the client.
 *
 * <h2>Event-driven, not polled</h2>
 *
 * <p>This used to scan all 41 inventory slots every 10 ticks, forever. It now does nothing per tick
 * beyond draining a queue that is empty almost always — the mixins in {@code com.gatedjei.mixin}
 * observe items as they enter the inventory and enqueue only what might teach something new.
 *
 * <p>Sweeps are one-shot, never periodic:
 * <ul>
 *   <li><b>on world join</b>, because an event design sees only <em>changes</em> — a player whose
 *       inventory is already full when they join, or who installs the mod into an existing save,
 *       would otherwise start with nothing discovered;</li>
 *   <li><b>on a liveness failure</b>, if no hook has fired after a grace period. That means the
 *       mixins did not apply, and discovery falls back to periodic sweeping rather than silently
 *       doing nothing for the rest of the session.</li>
 * </ul>
 *
 * <p>There is deliberately no periodic sweep in the healthy case. The trade-off is explicit: a
 * write that slips past every hook stays undiscovered for that save. The one structurally
 * unclosable route is another mod writing the public {@code Inventory.items} list directly, which
 * no mixin can intercept.
 */
@EventBusSubscriber(modid = GatedJei.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientDiscoveryHandler {

    private static int saveCounter = 0;
    private static final int SAVE_EVERY_TICKS = 600; // ~30s

    /**
     * How long to wait before concluding the hooks are dead.
     *
     * <p>{@code Inventory#setItem} fires on essentially any inventory activity, and the join sync
     * alone drives dozens of calls, so 30s of play without a single hook firing is conclusive.
     */
    private static final int LIVENESS_GRACE_TICKS = 600;

    private static int ticksSinceJoin = 0;
    private static boolean livenessChecked = false;
    private static boolean hooksDead = false;
    private static int fallbackSweepCounter = 0;

    private ClientDiscoveryHandler() {}

    // ---- lifecycle ----

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ticksSinceJoin = 0;
        livenessChecked = false;
        hooksDead = false;
        fallbackSweepCounter = 0;

        // Pending observations from a previous session must never land in this one's set.
        DiscoveryHooks.queue().clear();
        DiscoveryHooks.resetLiveness();

        ensureLoadedForCurrentSave();

        // Registries and capability registrations can differ per connection, so classify fresh.
        DiscoveryHooks.rebuildTables();

        // The join sweep: catch what is already being carried.
        sweepNow("world join");
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DiscoveryState state = DiscoveryState.get();
        // Drain first so anything observed in the final ticks is not lost.
        if (state.loadedKey() != null) {
            drain(state);
            DiscoveryStorage.save(state.loadedKey(), state.view(), state.viewFluids(), state.viewVariants(),
                    state.viewUnresolvedItemIds(), state.viewUnresolvedFluidIds());
        }
        DiscoveryHooks.queue().clear();
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

        // Nothing is applied until a save is loaded. Draining early would write discoveries that
        // replaceLoaded then wipes, leaving JEI showing a recipe whose discovery no longer exists.
        if (state.loadedKey() == null) {
            return;
        }

        drain(state);
        checkLiveness();

        if (hooksDead) {
            fallbackSweep();
        }

        maybeSave(state);
    }

    // ---- the per-tick work ----

    /**
     * Apply everything the hooks observed since the last tick.
     *
     * <p>The idle cost is one {@code isEmpty} check inside the queue. A batch of any size produces
     * at most one discovery delta per ingredient kind, so JEI is updated once rather than per slot.
     */
    private static void drain(DiscoveryState state) {
        DiscoveryHooks.queue().drainTo((items, fluids, variants) -> {
            if (!items.isEmpty()) {
                state.discoverAll(items);
            }
            if (!fluids.isEmpty()) {
                state.discoverAllFluids(fluids);
            }
            if (!variants.isEmpty()) {
                state.discoverAllVariants(variants);
            }
        });
    }

    /**
     * Decide once per session whether the mixins actually applied.
     *
     * <p>Nothing offline can prove a mixin applies, so this is the runtime answer: if no hook has
     * fired after the grace period, say so loudly and switch on periodic sweeping. Discovery then
     * degrades to roughly the old polling behaviour instead of silently ceasing.
     */
    private static void checkLiveness() {
        if (livenessChecked) {
            return;
        }
        if (++ticksSinceJoin < LIVENESS_GRACE_TICKS) {
            return;
        }
        livenessChecked = true;
        if (DiscoveryHooks.anyHookFired()) {
            return;
        }
        hooksDead = true;
        GatedJei.LOGGER.warn(
                "No discovery hook fired in {} ticks, so the mixins did not apply. Falling back to "
                        + "periodic inventory sweeps every {} ticks. If discovery seems to lag, check the "
                        + "log for 'Mixin environment was unable to detect the current side'.",
                LIVENESS_GRACE_TICKS, Config.orDefault(Config.SWEEP_INTERVAL_TICKS, 100));
    }

    /** Degraded mode only: periodic sweeping, standing in for the hooks that never applied. */
    private static void fallbackSweep() {
        int interval = Config.orDefault(Config.SWEEP_INTERVAL_TICKS, 100);
        if (interval <= 0) {
            return; // explicitly disabled
        }
        if (++fallbackSweepCounter < interval) {
            return;
        }
        fallbackSweepCounter = 0;
        sweepNow(null);
    }

    private static void maybeSave(DiscoveryState state) {
        if (++saveCounter >= SAVE_EVERY_TICKS) {
            saveCounter = 0;
            if (state.isDirty() && state.loadedKey() != null) {
                DiscoveryStorage.save(state.loadedKey(), state.view(), state.viewFluids(), state.viewVariants(),
                        state.viewUnresolvedItemIds(), state.viewUnresolvedFluidIds());
                state.clearDirty();
            }
        }
    }

    // ---- sweeps ----

    /**
     * Observe everything the player is currently carrying.
     *
     * <p>Routed through the same queue as the hooks, so it is comprehended identically and batched
     * into the same single delta.
     *
     * @param reason logged when non-null; null for routine fallback sweeps, which must stay quiet
     */
    public static void sweepNow(String reason) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        List<ItemStack> held = collectHeldStacks(player);
        for (ItemStack stack : held) {
            DiscoveryHooks.onSweep(stack);
        }
        if (reason != null) {
            GatedJei.LOGGER.debug("Swept {} carried stacks ({}).", held.size(), reason);
        }
    }

    /** Loads the discovery sets if they haven't been loaded for the current save key yet. */
    public static void ensureLoadedForCurrentSave() {
        DiscoveryState state = DiscoveryState.get();
        String key = SaveContext.currentKey();
        if (key.equals(state.loadedKey())) {
            return;
        }
        // The save changed under us. Anything still queued belongs to the previous one.
        DiscoveryHooks.queue().clear();

        DiscoveryStorage.Loaded loaded = DiscoveryStorage.load(key);
        state.replaceLoaded(key, loaded.items(), loaded.fluids(), loaded.variants(),
                loaded.unresolvedItemIds(), loaded.unresolvedFluidIds());
        GatedJei.LOGGER.info("Loaded {} items and {} fluids for save '{}'.",
                loaded.items().size(), loaded.fluids().size(), key);
    }

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
}
