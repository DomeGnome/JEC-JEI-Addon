package com.gatedjei;

import com.gatedjei.discovery.ClientDiscoveryHandler;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.jei.RecipeGate;
import com.gatedjei.notify.ComprehensionNotices;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
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
 *       Minecraft 1.21.1 the full recipe set is synced to the client (this stops being true on
 *       1.21.2+, where recipes live server-side — see README).</li>
 *   <li>The server does not need this mod installed.</li>
 * </ul>
 */
@Mod(GatedJei.MODID)
public final class GatedJei {
    public static final String MODID = "gatedjei";
    public static final Logger LOGGER = LoggerFactory.getLogger("GatedJei");

    public GatedJei(IEventBus modEventBus, ModContainer modContainer) {
        // Client-type config (this mod has no server-side behaviour).
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC, MODID + "-client.toml");

        // The single RecipeGate instance is the bridge between discovery and JEI.
        // It listens for newly discovered items and unhides the recipes they unlock.
        DiscoveryState.get().addListener(RecipeGate.INSTANCE::onNewlyDiscovered);
        // And for newly discovered fluids, to reveal them in JEI's fluid list.
        DiscoveryState.get().addFluidListener(RecipeGate.INSTANCE::onNewlyDiscoveredFluids);
        // And for newly discovered subtype variants (granular mode: individual enchantments/potions).
        DiscoveryState.get().addVariantListener(RecipeGate.INSTANCE::onNewlyDiscoveredVariants);

        // Our own "you comprehended something new" notice. OFF by default: it is the fallback for
        // players running no pickup-notification mod. Registered regardless so that turning the
        // config on takes effect at once; a pure observer, it can never affect what gets discovered.
        ComprehensionNotices.install();

        // Several toggles change what a held stack teaches (granularSubtypeDiscovery,
        // discoverBaseContainer, discoverFluidsByBucket) or what should be visible. With discovery
        // event-driven there is no periodic scan to notice, so re-observe and re-apply on reload.
        modEventBus.addListener(ModConfigEvent.Reloading.class, event -> {
            if (event.getConfig().getSpec() != Config.SPEC) {
                return;
            }
            ClientDiscoveryHandler.sweepNow("config reload");
            RecipeGate.INSTANCE.reapply();
        });

        LOGGER.info("JEI Gated Discovery loaded (client-side).");
    }
}
