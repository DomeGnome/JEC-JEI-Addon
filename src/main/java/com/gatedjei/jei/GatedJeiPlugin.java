package com.gatedjei.jei;

import com.gatedjei.GatedJei;
import com.gatedjei.discovery.ClientDiscoveryHandler;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI plugin entry point. JEI discovers this automatically via the {@link JeiPlugin} annotation.
 *
 * <p>{@link #onRuntimeAvailable} is called after JEI finishes loading (and again after every
 * /reload or resource reload), which is exactly when we (re)build our index and (re)apply gating.
 */
@JeiPlugin
public final class GatedJeiPlugin implements IModPlugin {
    // 1.20.1 uses the public ResourceLocation constructor. Forge 47.4+ back-ported the 1.21
    // fromNamespaceAndPath factory and marked the constructor deprecated-for-removal, hence the
    // build warning — but the factory is missing on earlier 47.x builds, so the constructor is the
    // form that works across the whole supported Forge range.
    private static final ResourceLocation UID = new ResourceLocation(GatedJei.MODID, "gated_discovery");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // Make sure the discovery set for the current save is loaded before we decide what to show.
        try {
            ClientDiscoveryHandler.ensureLoadedForCurrentSave();
        } catch (Throwable t) {
            GatedJei.LOGGER.warn("Could not load discovery set before applying gating: {}", t.toString());
        }
        RecipeGate.INSTANCE.onRuntimeAvailable(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        RecipeGate.INSTANCE.onRuntimeUnavailable();
    }
}
