package com.gatedjei;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config. All of the toggles requested in the spec live here.
 */
public final class Config {
    public enum DiscoveryScope {
        /** Discovery is tracked separately for each save / server connection. */
        PER_SAVE,
        /** A single discovery set shared across every world on this client. */
        GLOBAL
    }

    public enum UnresolvedPolicy {
        /** Recipes whose inputs we cannot read (custom modded categories) stay visible. */
        REVEAL,
        /** Such recipes stay hidden forever. Stricter, but can permanently hide modded content. */
        HIDE
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue REQUIRE_OUTPUTS_DISCOVERED;
    public static final ModConfigSpec.BooleanValue HIDE_UNDISCOVERED_ITEMS;
    public static final ModConfigSpec.BooleanValue HIDE_UNDISCOVERED_FLUIDS;
    public static final ModConfigSpec.BooleanValue GRANULAR_SUBTYPE_DISCOVERY;
    public static final ModConfigSpec.BooleanValue REVEAL_ALL;
    public static final ModConfigSpec.EnumValue<UnresolvedPolicy> UNRESOLVED_POLICY;
    public static final ModConfigSpec.EnumValue<DiscoveryScope> DISCOVERY_SCOPE;
    public static final ModConfigSpec.IntValue SWEEP_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue DISCOVER_FLUIDS_BY_BUCKET;
    public static final ModConfigSpec.BooleanValue DISCOVER_BASE_CONTAINER;
    public static final ModConfigSpec.BooleanValue LOG_STATS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("JEI Gated Discovery settings").push("gating");

        REQUIRE_OUTPUTS_DISCOVERED = b
                .comment("If true, a recipe's OUTPUT item(s) must also be discovered before the recipe is shown.",
                         "Default false: you can see a recipe as soon as you have all of its inputs.")
                .define("requireOutputsDiscovered", false);

        HIDE_UNDISCOVERED_ITEMS = b
                .comment("If true, also remove undiscovered items from JEI's item list (not just their recipes).",
                         "Default true: the item list starts empty and fills in as you discover items.")
                .define("hideUndiscoveredItems", true);

        HIDE_UNDISCOVERED_FLUIDS = b
                .comment("If true, hide undiscovered FLUIDS (water, lava, modded fluids) from JEI's fluid list.",
                         "Fluids are a separate JEI ingredient type, so this is needed on top of hideUndiscoveredItems.",
                         "Fluids are discovered by wading in them or holding a bucket of them (see [discovery]).")
                .define("hideUndiscoveredFluids", true);

        GRANULAR_SUBTYPE_DISCOVERY = b
                .comment("If true, items with NBT variants (enchanted books, potions, tipped arrows) are gated",
                         "per-variant instead of per-item. Discovering a Sharpness book reveals only the Sharpness",
                         "book (plus the plain book); discovering a Night Vision potion reveals only that potion.",
                         "If false, discovering any one variant reveals all of them.",
                         "Only affects JEI's item list; requires hideUndiscoveredItems = true to have any effect.")
                .define("granularSubtypeDiscovery", true);

        REVEAL_ALL = b
                .comment("DEBUG: if true, nothing is hidden. Use to confirm JEI integration / disable gating fast.")
                .define("revealAll", false);

        UNRESOLVED_POLICY = b
                .comment("What to do with recipes whose inputs cannot be read (custom modded JEI categories that",
                         "do not use vanilla-style ingredients). REVEAL keeps them visible; HIDE keeps them hidden.",
                         "Default HIDE: unreadable recipes stay gated, consistent with everything else being hidden.")
                .defineEnum("unresolvedRecipePolicy", UnresolvedPolicy.HIDE);

        b.pop();
        b.comment("Discovery tracking").push("discovery");

        DISCOVERY_SCOPE = b
                .comment("PER_SAVE: each save / server has its own discovery set (recommended, matches vanilla feel).",
                         "GLOBAL: one discovery set shared across all worlds on this client.")
                .defineEnum("discoveryScope", DiscoveryScope.PER_SAVE);

        SWEEP_INTERVAL_TICKS = b
                .comment("FALLBACK ONLY. Discovery is event-driven: items are observed as they enter your",
                         "inventory, so there is normally no periodic scanning at all.",
                         "If the mod detects that its hooks never fired (a NeoForge update or an",
                         "incompatible mod suppressed them) it logs a warning and falls back to sweeping",
                         "the inventory this often, in client ticks (20 = 1s). Set 0 to disable the",
                         "fallback entirely and accept that discovery stops working in that case.")
                .defineInRange("fallbackSweepIntervalTicks", 100, 0, 1200);

        DISCOVER_FLUIDS_BY_BUCKET = b
                .comment("Discover a fluid by holding a bucket (or other fluid container) of it",
                         "(e.g. a lava bucket in your inventory learns lava).",
                         "This is the ONLY way fluids are discovered. Wading through a fluid deliberately",
                         "teaches nothing: handling a fluid is what demonstrates comprehension of it.",
                         "Note that a modded fluid obtainable only inside machines, with no container item,",
                         "can therefore never be discovered -- set hideUndiscoveredFluids = false if a pack",
                         "you play has such fluids and you would rather see them all.")
                .define("discoverFluidsByBucket", true);

        DISCOVER_BASE_CONTAINER = b
                .comment("When you comprehend a 'filled' subtype item, also discover the empty/base item it's",
                         "built on — since you're literally holding both. Covers:",
                         "  filled bucket -> bucket, enchanted book -> book, potion -> glass bottle,",
                         "  tipped arrow -> arrow, soup -> bowl.",
                         "Set false to learn only the filled item itself.")
                .define("discoverBaseContainer", true);

        LOG_STATS = b
                .comment("Log discovery/recipe-gating stats to the log (also available via /gatedjei stats).")
                .define("logStats", false);

        b.pop();
        SPEC = b.build();
    }

    /**
     * Read a toggle that may be consulted before the config spec has loaded.
     *
     * <p>{@code ModConfigSpec.ConfigValue#get} throws {@code IllegalStateException} until the spec
     * is loaded. In a running client that happens early enough not to matter, but two paths can run
     * before it: unit tests (no FML config lifecycle at all), and anything reacting to a config
     * event during startup. Those used to surface as a swallowed exception that silently skipped
     * part of comprehension, which is worse than falling back to the documented default.
     */
    public static boolean orDefault(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** @see #orDefault(ModConfigSpec.BooleanValue, boolean) */
    public static int orDefault(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** @see #orDefault(ModConfigSpec.BooleanValue, boolean) */
    public static <T extends Enum<T>> T orDefault(ModConfigSpec.EnumValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private Config() {}
}
