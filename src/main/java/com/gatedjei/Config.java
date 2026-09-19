package com.gatedjei;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

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

    public enum SpellScrollDiscovery {
        /** Scrolls are gated per item, exactly as they were before scroll support existed. */
        OFF,
        /** One key per spell: touching any Fireball scroll reveals every Fireball scroll. */
        PER_SPELL,
        /** One key per spell AND level: a Fireball IV scroll reveals only Fireball IV. */
        PER_SPELL_AND_LEVEL
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
    public static final ModConfigSpec.EnumValue<SpellScrollDiscovery> IRONS_SPELL_SCROLL_DISCOVERY;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_CATEGORY_CATALYSTS;
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

        IRONS_SPELL_SCROLL_DISCOVERY = b
                .comment("Soft compat with Iron's Spells 'n Spellbooks: how finely spell scrolls",
                         "(irons_spellbooks:scroll) are gated. Iron's gives JEI one scroll entry per spell",
                         "PER LEVEL, so this decides what showing up means when you touch one.",
                         "  PER_SPELL_AND_LEVEL: a Fireball IV scroll reveals only Fireball IV (default;",
                         "                       the same strictness as an enchanted book of a given level).",
                         "  PER_SPELL:           a Fireball IV scroll reveals every Fireball scroll, all levels.",
                         "  OFF:                 scrolls are gated per item again - touching any one scroll",
                         "                       reveals the whole spell catalogue.",
                         "Both keys are always recorded, so switching this on an existing save takes effect",
                         "retroactively. Only affects Iron's scrolls; other spell-carrying items (spell books,",
                         "magic swords, imbued armor) are gated per item because Iron's does not give JEI",
                         "per-variant entries for them. Inert if Iron's isn't installed.",
                         "Requires granularSubtypeDiscovery = true and hideUndiscoveredItems = true.")
                .defineEnum("ironsSpellScrollDiscovery", SpellScrollDiscovery.PER_SPELL_AND_LEVEL);

        EXTRA_CATEGORY_CATALYSTS = b
                .comment("Require a machine before its recipe tab appears, for categories that don't ask for one.",
                         "",
                         "JEI already does this on its own: a recipe category disappears while every item it",
                         "registered as a 'catalyst' is hidden, which is why the smelting tab only shows up once",
                         "you have touched a furnace, brewing once you have touched a stand, and so on for most",
                         "modded machines. But a category that registers NO catalyst is never hidden that way, so",
                         "it shows from world load. Create's Sequenced Assembly is one of those: every recipe in",
                         "it needs a Deployer, yet Create registers no catalyst for the category.",
                         "",
                         "Each entry is  <recipe category id>=<item id>[,<item id>...]  and the tab appears once",
                         "you have discovered ANY ONE of the listed items - the same rule JEI uses for its own",
                         "catalysts. Entries naming a category or item that isn't installed are skipped, so it is",
                         "safe to list mods you don't have. Set to [] to disable.",
                         "Has no effect while hideUndiscoveredItems = false or revealAll = true, since the vanilla",
                         "workstation gating this mirrors is switched off then too.")
                .defineListAllowEmpty("extraCategoryCatalysts",
                        List.of("create:sequenced_assembly=create:deployer"),
                        () -> "modid:category=modid:item",
                        o -> o instanceof String str && !str.isBlank());

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

    private Config() {}
}
