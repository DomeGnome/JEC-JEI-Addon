package com.gatedjei.command;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.discovery.DiscoveryStorage;
import com.gatedjei.jei.RecipeGate;
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
 * Client-side debug commands: {@code /gatedjei <stats|reveal|reset|discoverall>}.
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
                    DiscoveryState state = DiscoveryState.get();
                    String key = state.loadedKey();
                    state.replaceLoaded(key, new java.util.HashSet<>(), new java.util.HashSet<>(), new java.util.HashSet<>());
                    if (key != null) {
                        DiscoveryStorage.save(key, state.view(), state.viewFluids(), state.viewVariants());
                    }
                    RecipeGate.INSTANCE.reapply();
                    say(ctx.getSource(), "Discovery reset for this save (items + fluids). Recipes re-hidden.");
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("discoverall").executes(ctx -> {
                    // Marks every registered item AND fluid discovered (useful for testing the unhide path).
                    List<Item> everything = new ArrayList<>();
                    for (Item item : BuiltInRegistries.ITEM) {
                        everything.add(item);
                    }
                    DiscoveryState.get().discoverAll(everything);
                    List<net.minecraft.world.level.material.Fluid> allFluids = new ArrayList<>();
                    for (net.minecraft.world.level.material.Fluid fluid : BuiltInRegistries.FLUID) {
                        allFluids.add(fluid);
                    }
                    DiscoveryState.get().discoverAllFluids(allFluids);
                    say(ctx.getSource(), "Discovered all " + everything.size() + " items and " + allFluids.size() + " fluids.");
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
