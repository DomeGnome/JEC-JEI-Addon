package com.gatedjei.command;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.discovery.DiscoveryStorage;
import com.gatedjei.jei.RecipeGate;
import com.gatedjei.notify.ComprehensionNotices;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side debug commands:
 * {@code /gatedjei <stats|reveal|reset|discoverheld|discoverall|notifytest>}.
 * These never touch the server; they only affect the local JEI view / discovery file.
 */
@EventBusSubscriber(modid = GatedJei.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class GateCommands {
    private GateCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = net.minecraft.commands.Commands.literal("gatedjei")
                .then(net.minecraft.commands.Commands.literal("stats").executes(ctx -> {
                    say(ctx.getSource(), String.format(
                            "Discovered items: %d | fluids: %d | Recipes visible: %d / %d | scope: %s",
                            DiscoveryState.get().count(),
                            DiscoveryState.get().fluidCount(),
                            RecipeGate.INSTANCE.visibleRecipes(),
                            RecipeGate.INSTANCE.totalRecipes(),
                            Config.DISCOVERY_SCOPE.get()));
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("reveal").executes(ctx -> {
                    // toggles the debug reveal-all config in memory and re-applies
                    boolean now = !Config.REVEAL_ALL.get();
                    Config.REVEAL_ALL.set(now);
                    RecipeGate.INSTANCE.reapply();
                    say(ctx.getSource(), "revealAll is now " + now);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("reset").executes(ctx -> {
                    // Wiping and re-deriving the whole set is bookkeeping, not discovery: stay quiet
                    // through it. The re-sweep below only ENQUEUES, so the delta lands on a later
                    // tick -- hence a timed window as well as the synchronous quiet block.
                    ComprehensionNotices.feed().suppressForTicks(60);
                    DiscoveryState state = DiscoveryState.get();
                    String key = state.loadedKey();
                    state.replaceLoaded(key, new java.util.HashSet<>(), new java.util.HashSet<>(), new java.util.HashSet<>());
                    if (key != null) {
                        DiscoveryStorage.save(key, state.view(), state.viewFluids(), state.viewVariants(),
                                state.viewUnresolvedItemIds(), state.viewUnresolvedFluidIds());
                    }
                    RecipeGate.INSTANCE.reapply();
                    // Re-observe what the player is carrying. The old polling scan did this a tick
                    // later by itself; with discovery event-driven, the reset has to ask for it.
                    com.gatedjei.discovery.ClientDiscoveryHandler.sweepNow("reset");
                    say(ctx.getSource(), "Discovery reset for this save (items + fluids). Recipes re-hidden.");
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("discoverheld").executes(ctx -> {
                    // Re-derives discovery from what you are actually carrying, with the fast path
                    // bypassed. This is the manual remedy for the one miss route no mixin can
                    // intercept -- Inventory.items is a public field, so another mod writing it
                    // directly is invisible to every hook.
                    //
                    // It doubles as a diagnostic: if this reports new discoveries for items you
                    // have plainly been holding, something upstream is not working.
                    DiscoveryState state = DiscoveryState.get();
                    int itemsBefore = state.count();
                    int fluidsBefore = state.fluidCount();
                    int variantsBefore = state.variantCount();

                    int swept = com.gatedjei.discovery.ClientDiscoveryHandler.forcedSweepNow();

                    int newItems = state.count() - itemsBefore;
                    int newFluids = state.fluidCount() - fluidsBefore;
                    int newVariants = state.variantCount() - variantsBefore;

                    if (newItems == 0 && newFluids == 0 && newVariants == 0) {
                        say(ctx.getSource(), String.format(
                                "Swept %d held stacks: nothing new. Discovery is up to date.", swept));
                    } else {
                        say(ctx.getSource(), String.format(
                                "Swept %d held stacks: %d new item(s), %d new fluid(s), %d new variant(s). "
                                        + "Anything new here means it was not caught when you obtained it.",
                                swept, newItems, newFluids, newVariants));
                    }
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("discoverall").executes(ctx -> {
                    // Marks every registered item AND fluid discovered (useful for testing the unhide path).
                    List<Item> everything = new ArrayList<>();
                    for (Item item : BuiltInRegistries.ITEM) {
                        everything.add(item);
                    }
                    List<net.minecraft.world.level.material.Fluid> allFluids = new ArrayList<>();
                    for (net.minecraft.world.level.material.Fluid fluid : BuiltInRegistries.FLUID) {
                        allFluids.add(fluid);
                    }
                    // Comprehending the entire registry must not produce a notification; the command
                    // already reports what it did.
                    ComprehensionNotices.feed().suppressForTicks(60);
                    ComprehensionNotices.runQuietly(() -> {
                        DiscoveryState.get().discoverAll(everything);
                        DiscoveryState.get().discoverAllFluids(allFluids);
                        // Also force-comprehend what is held, so subtype VARIANTS of carried items
                        // (specific enchantments / potions) are recorded too. Without this, granular
                        // mode left every book and potion variant hidden after a discoverall, which
                        // made the command useless for testing the unhide path it exists to test.
                        com.gatedjei.discovery.ClientDiscoveryHandler.forcedSweepNow();
                    });
                    say(ctx.getSource(), "Discovered all " + everything.size() + " items and "
                            + allFluids.size() + " fluids (plus variants of anything held).");
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("compat").executes(ctx -> {
                    // The only in-game way to tell whether a pickup-display integration is live.
                    // "present(off)" means the mod is installed but its decoration is switched off;
                    // "decorated" means we are marking its rows. It does NOT prove the mixin
                    // applied -- that cannot be checked from here, only seen on a first-ever
                    // pickup -- but it does separate "the mod is missing" from "the config says no",
                    // which is the question people actually ask first.
                    say(ctx.getSource(), "Pickup displays: " + com.gatedjei.compat.ExternalDisplay.summary());
                    say(ctx.getSource(), com.gatedjei.compat.ExternalDisplay.supersedesOwnNotification()
                            ? "Our own notification is live, but stands down per-item for anything a "
                              + "decorated row already badged (ground pickups only -- crafting, chests, "
                              + "trades and /give are still announced by us)."
                            : "Our own notification announces every new comprehension.");
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("notifytest").executes(ctx -> {
                    // Puts the real notification on screen on demand, bypassing batching, the rate
                    // limit and the master switch. Rendering cannot be verified offline, so there has
                    // to be a way to look at it without waiting for a genuine discovery.
                    Minecraft mc = Minecraft.getInstance();
                    List<Item> sample = new ArrayList<>();
                    if (mc.player != null) {
                        for (net.minecraft.world.item.ItemStack stack : mc.player.getInventory().items) {
                            if (stack != null && !stack.isEmpty() && !sample.contains(stack.getItem())) {
                                sample.add(stack.getItem());
                                if (sample.size() >= 3) {
                                    break;
                                }
                            }
                        }
                    }
                    if (sample.isEmpty()) {
                        sample.add(net.minecraft.world.item.Items.DIAMOND);
                    }
                    boolean shown = ComprehensionNotices.presentNow(sample, sample.size());
                    say(ctx.getSource(), shown
                            ? "Test notice shown with " + sample.size() + " item(s), style "
                                    + Config.orDefault(Config.NOTIFICATION_STYLE, Config.NotificationStyle.TOAST)
                                    + ". notifyOnNewComprehension is "
                                    + Config.orDefault(Config.NOTIFY_ON_NEW_COMPREHENSION, false) + "."
                            : "Could not show a test notice (no client player).");
                    return 1;
                }));

        event.getDispatcher().register(root);
    }

    private static void say(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal("[GatedJEI] " + msg), false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            GatedJei.LOGGER.info(msg);
        }
    }
}
