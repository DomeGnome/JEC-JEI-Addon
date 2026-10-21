package com.gatedjei.compat;

/**
 * Gate for {@code gatedjei.pickupnotifier.mixins.json}.
 *
 * @see CompatMixinPlugin
 */
public final class PickupNotifierMixinPlugin extends CompatMixinPlugin {
    @Override
    protected String requiredModId() {
        return CompatModIds.PICKUP_NOTIFIER;
    }
}
