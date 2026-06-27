package com.gatedjei.discovery;

import com.gatedjei.GatedJei;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * "Museum mode": sneak + right-click an item frame to learn whatever item it displays,
 * exactly as if the player had held it. The sneak-click is consumed, so the frame does NOT rotate.
 *
 * <p>Item frames route a right-click through TWO events — {@link PlayerInteractEvent.EntityInteractSpecific}
 * fires first, then {@link PlayerInteractEvent.EntityInteract} (where vanilla rotation happens). A
 * client-side mod has to consume BOTH on a sneak-click to reliably stop the rotation, so we handle both.
 *
 * <p>Client-only on purpose: cancelling the interaction on the client stops the rotation packet from
 * ever being sent, so it works in singleplayer and against servers that don't have this mod.
 *
 * <p>Normal (non-sneak) right-clicks are untouched, so frames still rotate as usual.
 */
@EventBusSubscriber(modid = GatedJei.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ItemFrameDiscoveryHandler {
    private ItemFrameDiscoveryHandler() {}

    @SubscribeEvent
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (tryDiscover(event.getLevel().isClientSide(), event.getHand(), event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (tryDiscover(event.getLevel().isClientSide(), event.getHand(), event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * If this is a sneak-click on a filled item frame, learn its item and return {@code true}
     * (telling the caller to cancel the event so the frame doesn't rotate). Discovery is idempotent,
     * and the action-bar message only shows the first time, so it's safe to call from both events.
     */
    private static boolean tryDiscover(boolean clientSide, InteractionHand hand, Player player, Entity target) {
        if (!clientSide) {
            return false;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return false; // ignore the off-hand pass so we don't double-fire
        }
        if (player == null || !player.isShiftKeyDown()) {
            return false; // only sneak-clicks discover; normal clicks still rotate
        }
        if (!(target instanceof ItemFrame frame)) {
            return false; // covers GlowItemFrame too (subclass)
        }
        ItemStack shown = frame.getItem();
        if (shown.isEmpty()) {
            return false; // empty frame: leave vanilla placement alone
        }

        // Comprehend the displayed item fully: the item, any fluid it holds, and (for a fluid
        // container) the emptied container — e.g. a water bucket teaches water_bucket + water + bucket.
        Set<net.minecraft.world.item.Item> items = new HashSet<>();
        Set<net.minecraft.world.level.material.Fluid> fluids = new HashSet<>();
        Set<String> variants = new HashSet<>();
        ItemComprehension.comprehend(shown, items, fluids, variants);

        boolean isNew = false;
        for (net.minecraft.world.item.Item it : items) {
            if (!DiscoveryState.get().isDiscovered(it)) {
                isNew = true;
                break;
            }
        }
        if (!isNew) {
            for (net.minecraft.world.level.material.Fluid fl : fluids) {
                if (!DiscoveryState.get().isDiscovered(fl)) {
                    isNew = true;
                    break;
                }
            }
        }
        if (!isNew) {
            for (String v : variants) {
                if (!DiscoveryState.get().isVariantDiscovered(v)) {
                    isNew = true;
                    break;
                }
            }
        }

        DiscoveryState.get().discoverAll(items);
        DiscoveryState.get().discoverAllFluids(fluids);
        DiscoveryState.get().discoverAllVariants(variants);

        if (isNew) {
            player.displayClientMessage(
                    Component.literal("Discovered: ").append(shown.getHoverName()), true);
        }
        return true;
    }
}
