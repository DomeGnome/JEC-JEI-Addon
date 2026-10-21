package com.gatedjei.compat;

import com.gatedjei.Config;
import net.neoforged.fml.ModList;

/**
 * The third-party pickup displays we can decorate, and whether each is live right now.
 *
 * <h2>Soft dependency</h2>
 *
 * <p>Nothing in this file names a third-party type. Presence is a string lookup through
 * {@link ModList}, which is a NeoForge API and always present, so this class classloads cleanly with
 * none of these mods installed. The classes that <em>do</em> name third-party types are the mixins
 * in {@code com.gatedjei.compat.mixin.*}, and those are only ever classloaded if the corresponding
 * mod is present — see {@link CompatMixinPlugin}.
 *
 * <p>{@code ModList.isLoaded} is cached on first use. It is cheap, but it is consulted from a render
 * path, and the answer cannot change within a launch.
 */
public enum ExternalDisplay {

    /** Pick Up Notifier (Fuzs). Decoration is on by default: verified target, trivial hook. */
    PICKUP_NOTIFIER(CompatModIds.PICKUP_NOTIFIER) {
        @Override
        boolean decorationEnabled() {
            return Config.orDefault(Config.DECORATE_PICKUP_NOTIFIER, true);
        }
    },

    /** Loot Journal (Obscuria). Decoration is off by default — see {@link CompatModIds#LOOT_JOURNAL}. */
    LOOT_JOURNAL(CompatModIds.LOOT_JOURNAL) {
        @Override
        boolean decorationEnabled() {
            return Config.orDefault(Config.DECORATE_LOOT_JOURNAL, false);
        }
    };

    private final String modId;

    /** Three-valued on purpose: null means "not asked yet", which is not the same as "absent". */
    private volatile Boolean present;

    ExternalDisplay(String modId) {
        this.modId = modId;
    }

    public String modId() {
        return modId;
    }

    /** Whether this specific decoration is switched on in the config. */
    abstract boolean decorationEnabled();

    /** Whether the mod is installed. Cached; false if asked before FML has a mod list. */
    public boolean present() {
        Boolean cached = present;
        if (cached != null) {
            return cached;
        }
        boolean found;
        try {
            ModList list = ModList.get();
            if (list == null) {
                return false; // too early to answer, and too early to cache an answer
            }
            found = list.isLoaded(modId);
        } catch (Throwable t) {
            found = false;
        }
        present = found;
        return found;
    }

    /**
     * Whether we are actually decorating this display: installed, switched on, and not globally
     * disabled. This is the single predicate every decoration path consults.
     */
    public boolean active() {
        return Config.orDefault(Config.DECORATE_EXTERNAL_PICKUP_DISPLAYS, true)
                && decorationEnabled()
                && present();
    }

    // ---- the arbiter our own notification asks ----

    /**
     * Whether any third-party display is currently being given our novelty marker.
     *
     * @see #supersedesOwnNotification()
     */
    public static boolean anyDecorationActive() {
        for (ExternalDisplay display : values()) {
            if (display.active()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether third-party rows are being badged, so our own notices defer to them per item.
     *
     * <p>NOTE: this does NOT disable our notification channel. Supersession is applied per notice,
     * against the specific novelty keys a row actually badged — see
     * {@code ComprehensionNotices#notSupersededBy}. These mods show ground pickups only, so
     * crafting, chests, trades, {@code /give} and {@code /gatedjei discoverheld} remain ours to
     * announce. This method exists for {@code /gatedjei compat} reporting.
     *
     * <p>This is the whole answer to the double-notification problem, and it is a config question
     * rather than a timing one: the two channels are driven by different things (their row is
     * decorated at the instant of the pickup, ours is batched off the discovery delta a tick later),
     * so trying to arbitrate between them per event would be fragile. Asking once, up front, whether
     * a decorated display exists is both simpler and what the player actually means.
     *
     * <p>Claim exclusivity in {@link NoveltySightings} is a separate and narrower guarantee: it stops
     * two <em>rows</em> being marked for the same kind. It does not and should not decide whether a
     * different channel also speaks.
     */
    public static boolean supersedesOwnNotification() {
        if (Config.orDefault(Config.NOTIFY_ALONGSIDE_EXTERNAL_DISPLAYS, false)) {
            return false;
        }
        return anyDecorationActive();
    }

    /** Whether any known display is installed, decorated or not. For {@code /gatedjei stats}. */
    public static boolean anyPresent() {
        for (ExternalDisplay display : values()) {
            if (display.present()) {
                return true;
            }
        }
        return false;
    }

    /** A one-line description of the compat situation, for diagnostics. */
    public static String summary() {
        StringBuilder out = new StringBuilder();
        for (ExternalDisplay display : values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(display.modId()).append('=')
                    .append(!display.present() ? "absent" : display.active() ? "decorated" : "present(off)");
        }
        return out.toString();
    }
}
