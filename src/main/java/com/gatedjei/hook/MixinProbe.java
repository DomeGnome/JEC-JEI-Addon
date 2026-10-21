package com.gatedjei.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain (non-mixin) landing pad for the proof mixin.
 *
 * <p>This deliberately lives OUTSIDE the {@code com.gatedjei.mixin} package: Mixin claims the
 * package declared in the mixin config, and any class in it that is not a registered mixin fails
 * to classload at runtime. Real hook logic must therefore live in an ordinary package like this
 * one, with the mixin itself being nothing but a one-line shim.
 */
public final class MixinProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("gatedjei/mixin");
    private static boolean logged;

    private MixinProbe() {}

    /** Logs exactly once, the first time an inventory slot write is observed. */
    public static void onInventorySetItem() {
        if (!logged) {
            logged = true;
            LOGGER.info("[gatedjei] Inventory#setItem mixin is live");
        }
    }
}
