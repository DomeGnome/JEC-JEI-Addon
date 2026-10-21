package com.gatedjei.hook;

import com.gatedjei.GatedJei;
import com.gatedjei.discovery.ComprehensionTables;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.discovery.ObservationQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * The landing pad every mixin calls into.
 *
 * <p>Lives outside {@code com.gatedjei.mixin} on purpose: Mixin claims the package named in its
 * config, and a non-mixin class there fails to classload at runtime. Each mixin is therefore a
 * one-line shim that delegates here.
 *
 * <h2>What every entry point must do, in order</h2>
 * <ol>
 *   <li><b>Guard on the local player first.</b> In singleplayer the same methods run on the
 *       integrated server thread for the {@code ServerPlayer}'s inventory. The guard must precede
 *       any shared-state read, so an off-thread call touches nothing of ours.</li>
 *   <li><b>Never throw.</b> {@code Inventory#add} wraps its body and rethrows as a crash report,
 *       and an exception during container-slot handling can desync the connection.</li>
 *   <li><b>Take the fast path.</b> Most observations are of items already known, and those cost a
 *       couple of lookups and nothing else.</li>
 * </ol>
 */
public final class DiscoveryHooks {

    private static final ObservationQueue QUEUE = new ObservationQueue();

    /** Static so the hot path allocates nothing per call. */
    private static final Predicate<Item> KNOWN = item -> DiscoveryState.get().isDiscovered(item);

    /** Rebuilt per world join: registries and capability registrations can differ per connection. */
    private static volatile ComprehensionTables tables = ComprehensionTables.empty();

    /**
     * Whether any hook has fired since the last reset.
     *
     * <p>Used by the liveness check: {@code Inventory#setItem} fires constantly in normal play, so
     * if nothing has fired after a while the mixins did not apply and discovery must fall back.
     *
     * <p>Set by the mixin entry points only, and only once their local-player guard has passed.
     * Sweeps do not set it, since a sweep is our own code and would mask a dead hook.
     */
    private static volatile boolean anyHookFired;

    private static boolean warnedHookFailure;

    private DiscoveryHooks() {}

    // ---- lifecycle ----

    public static ObservationQueue queue() {
        return QUEUE;
    }

    public static void rebuildTables() {
        tables = ComprehensionTables.build();
    }

    public static ComprehensionTables tables() {
        return tables;
    }

    public static boolean anyHookFired() {
        return anyHookFired;
    }

    public static void resetLiveness() {
        anyHookFired = false;
    }

    // ---- hook entry points, one per mixin injection ----

    /** {@code Inventory#setItem}: the universal choke point for slot writes. */
    public static void onSlotWrite(Inventory inventory, ItemStack stack) {
        if (!isLocal(inventory)) {
            return;
        }
        anyHookFired = true;
        observe(stack, ObservationQueue.Cause.SLOT);
    }

    /** {@code Inventory#add}: ground pickups, crafting remainders, hotbar-swap overflow. */
    public static void onAdd(Inventory inventory, ItemStack stack) {
        if (!isLocal(inventory)) {
            return;
        }
        anyHookFired = true;
        observe(stack, ObservationQueue.Cause.ADD);
    }

    /** {@code Inventory#setPickedItem}: creative middle-click pick-block. */
    public static void onPickBlock(Inventory inventory, ItemStack stack) {
        if (!isLocal(inventory)) {
            return;
        }
        anyHookFired = true;
        observe(stack, ObservationQueue.Cause.PICK_BLOCK);
    }

    /** {@code AbstractContainerMenu#setCarried}: the cursor stack. */
    public static void onCursor(AbstractContainerMenu menu, ItemStack stack) {
        if (!isLocalMenu(menu)) {
            return;
        }
        anyHookFired = true;
        observe(stack, ObservationQueue.Cause.CURSOR);
    }

    /**
     * {@code AbstractContainerMenu#initializeContents}: a bulk sync.
     *
     * <p>Only the carried stack is taken here. The item list is delivered through
     * {@code Slot#set -> Inventory#setItem}, which {@link #onSlotWrite} already sees — but the
     * carried stack is assigned to the field directly and never announced again, so it would
     * otherwise be missed permanently.
     */
    public static void onBulkSync(AbstractContainerMenu menu, ItemStack carried) {
        if (!isLocalMenu(menu)) {
            return;
        }
        anyHookFired = true;
        observe(carried, ObservationQueue.Cause.SYNC);
    }

    /**
     * {@code Player#setItemSlot}: writes {@code inventory.items/armor/offhand} directly.
     *
     * <p>The single most important hook for the fluid feature: this is the only client-side path
     * for filling your last bucket or bottle, milking a cow, bucketing a fish, stew-to-bowl, every
     * cauldron interaction, and the offhand swap.
     */
    public static void onEquipmentSlot(Player player, ItemStack stack) {
        if (!isLocalPlayer(player)) {
            return;
        }
        anyHookFired = true;
        observe(stack, ObservationQueue.Cause.EQUIPMENT);
    }

    // ---- shared body ----

    /**
     * Observe a stack found by a reconciliation sweep.
     *
     * <p>Deliberately does NOT mark liveness: a sweep is our own code running, so counting it
     * would make the liveness check incapable of ever detecting that the mixins failed to apply.
     * No local-player guard either — the caller is the client tick handler and already knows.
     */
    public static void onSweep(ItemStack stack) {
        observe(stack, ObservationQueue.Cause.SWEEP);
    }

    /**
     * Observe a stack with the fast path deliberately bypassed.
     *
     * <p>For {@code /gatedjei discoverheld}. A normal sweep consults
     * {@link ComprehensionTables#nothingLeftToTeach}, so it cannot tell you whether a stack was
     * missed because a hook never fired or because the fast path wrongly skipped it. This forces
     * full comprehension of everything, which makes the command a diagnostic: if it reports new
     * discoveries for items you have plainly been carrying, something upstream is not working.
     *
     * <p>Also marks no liveness, for the same reason sweeps do not.
     */
    public static void onForcedSweep(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        QUEUE.observe(stack, ObservationQueue.Cause.SWEEP);
    }

    private static void observe(ItemStack stack, ObservationQueue.Cause cause) {
        try {
            if (tables.nothingLeftToTeach(stack, KNOWN)) {
                return;
            }
            QUEUE.observe(stack, cause);
        } catch (Throwable t) {
            if (!warnedHookFailure) {
                warnedHookFailure = true;
                GatedJei.LOGGER.warn("Discovery hook failed ({}), continuing: {}", cause, t.toString());
            }
        }
    }

    // ---- local-player guards ----

    /**
     * True only for the client player's own inventory.
     *
     * <p>Identity comparison against {@code Minecraft.getInstance().player} is what excludes the
     * integrated server's {@code ServerPlayer}, whose {@code Inventory} is a different instance
     * living on a different thread.
     */
    private static boolean isLocal(Inventory inventory) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || inventory == null) {
                return false;
            }
            return inventory == mc.player.getInventory();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalPlayer(Player player) {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && player != null && player == mc.player;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True for a menu belonging to the client player.
     *
     * <p>Both are checked: {@code inventoryMenu} is the always-present one, {@code containerMenu} is
     * whatever screen is open (and equals {@code inventoryMenu} when none is).
     */
    private static boolean isLocalMenu(AbstractContainerMenu menu) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || menu == null) {
                return false;
            }
            return menu == mc.player.containerMenu || menu == mc.player.inventoryMenu;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
