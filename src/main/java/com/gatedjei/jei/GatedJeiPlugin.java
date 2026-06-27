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
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(GatedJei.MODID, "gated_discovery");

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
        // TODO(verify): onRuntimeUnavailable exists on IModPlugin in JEI 19.x. If your build lacks it,
        // just delete this override — rebuild-on-onRuntimeAvailable still keeps things correct.
        RecipeGate.INSTANCE.onRuntimeUnavailable();
    }
}
