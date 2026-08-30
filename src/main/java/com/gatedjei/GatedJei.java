package com.gatedjei;

import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.jei.RecipeGate;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JEI Gated Discovery.
 *
 * <p>Hides every JEI recipe on world load and only reveals a recipe once the player has
 * "discovered" (held in inventory at least once) a matching item for every one of its inputs.
 *
 * <p>This is intentionally a <b>client-side-only</b> mod:
 * <ul>
 *   <li>Discovery is detected by scanning the local player's inventory on the client.</li>
 *   <li>Hiding/unhiding is done through JEI's client-side runtime recipe manager.</li>
 *   <li>It therefore works in singleplayer AND when connected to a dedicated server, because on
 *       Minecraft 1.20.1 the full recipe set is synced to the client (this stops being true on
 *       1.21.2+, where recipes live server-side — see README).</li>
 *   <li>The server does not need this mod installed.</li>
 * </ul>
 *
 * <p><b>1.20.1 / Forge port.</b> Forge constructs the mod class with no arguments, so the config is
 * registered through {@link ModLoadingContext} rather than through a constructor-injected mod
 * container (the NeoForge 1.21 style). Event handlers all live on the game bus and register
 * themselves via {@code @Mod.EventBusSubscriber}, so the mod event bus is not needed here.
 */
@Mod(GatedJei.MODID)
public final class GatedJei {
    public static final String MODID = "gatedjei";
    public static final Logger LOGGER = LoggerFactory.getLogger("GatedJei");

    public GatedJei() {
        // Client-type config (this mod has no server-side behaviour).
        // ModLoadingContext.get() is flagged deprecated-for-removal by the newest Forge 47.x builds,
        // which prefer constructor injection — but that only exists from 47.1 onwards, and this mod
        // supports the whole 47.x line, so the static accessor stays. It is not going anywhere on
        // 1.20.1; the deprecation is a forward-port nudge.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC, MODID + "-client.toml");

        // The single RecipeGate instance is the bridge between discovery and JEI.
        // It listens for newly discovered items and unhides the recipes they unlock.
        DiscoveryState.get().addListener(RecipeGate.INSTANCE::onNewlyDiscovered);
        // And for newly discovered fluids, to reveal them in JEI's fluid list.
        DiscoveryState.get().addFluidListener(RecipeGate.INSTANCE::onNewlyDiscoveredFluids);
        // And for newly discovered subtype variants (granular mode: individual enchantments/potions).
        DiscoveryState.get().addVariantListener(RecipeGate.INSTANCE::onNewlyDiscoveredVariants);

        LOGGER.info("JEI Gated Discovery loaded (client-side).");
    }
}
