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
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue DISCOVER_FLUIDS_BY_WADING;
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
                         "Default false: only recipes are gated; the item list is left alone.")
                .define("hideUndiscoveredItems", false);

        HIDE_UNDISCOVERED_FLUIDS = b
                .comment("If true, hide undiscovered FLUIDS (water, lava, modded fluids) from JEI's fluid list,",
                         "the same way hideUndiscoveredItems hides items. Without this, fluids always show because",
                         "fluids are a separate JEI ingredient type from items.",
                         "Fluids are discovered by wading in them or holding a bucket of them (see [discovery]).")
                .define("hideUndiscoveredFluids", true);

        GRANULAR_SUBTYPE_DISCOVERY = b
                .comment("If true, items with NBT variants (enchanted books, potions, tipped arrows) are gated",
                         "per-variant instead of per-item. Discovering a Sharpness book reveals only the Sharpness",
                         "book (plus the plain book); discovering a Night Vision potion reveals only that potion.",
                         "If false, discovering any one variant reveals all of them (simpler, the pre-1.0.5 behavior).",
                         "Only affects JEI's item list; requires hideUndiscoveredItems = true to have any effect.")
                .define("granularSubtypeDiscovery", false);

        REVEAL_ALL = b
                .comment("DEBUG: if true, nothing is hidden. Use to confirm JEI integration / disable gating fast.")
                .define("revealAll", false);

        UNRESOLVED_POLICY = b
                .comment("What to do with recipes whose inputs cannot be read (custom modded JEI categories that",
                         "do not use vanilla-style ingredients). REVEAL keeps them visible; HIDE keeps them hidden.")
                .defineEnum("unresolvedRecipePolicy", UnresolvedPolicy.REVEAL);

        b.pop();
        b.comment("Discovery tracking").push("discovery");

        DISCOVERY_SCOPE = b
                .comment("PER_SAVE: each save / server has its own discovery set (recommended, matches vanilla feel).",
                         "GLOBAL: one discovery set shared across all worlds on this client.")
                .defineEnum("discoveryScope", DiscoveryScope.PER_SAVE);

        SCAN_INTERVAL_TICKS = b
                .comment("How often (in client ticks, 20 = 1s) to scan the inventory for newly touched items.")
                .defineInRange("scanIntervalTicks", 10, 1, 200);

        DISCOVER_FLUIDS_BY_WADING = b
                .comment("Discover a fluid by standing in / wading through it (e.g. step into water to learn water).")
                .define("discoverFluidsByWading", true);

        DISCOVER_FLUIDS_BY_BUCKET = b
                .comment("Discover a fluid by holding a bucket (or other fluid container) of it",
                         "(e.g. a lava bucket in your inventory learns lava).")
                .define("discoverFluidsByBucket", true);

        DISCOVER_BASE_CONTAINER = b
                .comment("When you comprehend a 'filled' subtype item, also discover the empty/base item it's",
                         "built on — since you're literally holding both. Covers:",
                         "  filled bucket -> bucket, enchanted book -> book, potion -> glass bottle, tipped arrow -> arrow.",
                         "Set false to learn only the filled item itself.")
                .define("discoverBaseContainer", true);

        LOG_STATS = b
                .comment("Log discovery/recipe-gating stats to the log (also available via /gatedjei stats).")
                .define("logStats", false);

        b.pop();
        SPEC = b.build();
    }

    private Config() {}
}
