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

    /** How our own (fallback) "new comprehension" notice is drawn. */
    public enum NotificationStyle {
        /** A vanilla toast in the top-right corner: item icon, title, one line of text. */
        TOAST,
        /** A single line above the hotbar. Cheapest and quietest; no icon. */
        ACTION_BAR,
        /** A chat line, which stays in the log and can be scrolled back to. */
        CHAT
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

    public static final ModConfigSpec.BooleanValue DECORATE_EXTERNAL_PICKUP_DISPLAYS;
    public static final ModConfigSpec.BooleanValue DECORATE_PICKUP_NOTIFIER;
    public static final ModConfigSpec.BooleanValue DECORATE_LOOT_JOURNAL;
    public static final ModConfigSpec.BooleanValue NOTIFY_ALONGSIDE_EXTERNAL_DISPLAYS;
    public static final ModConfigSpec.ConfigValue<String> NOVELTY_GLYPH;
    public static final ModConfigSpec.IntValue NOVELTY_COLOR;

    public static final ModConfigSpec.BooleanValue NOTIFY_ON_NEW_COMPREHENSION;
    public static final ModConfigSpec.EnumValue<NotificationStyle> NOTIFICATION_STYLE;
    public static final ModConfigSpec.IntValue NOTIFICATION_COALESCE_TICKS;
    public static final ModConfigSpec.IntValue NOTIFICATION_MIN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue NOTIFICATION_BULK_THRESHOLD;
    public static final ModConfigSpec.IntValue NOTIFICATION_MAX_ICONS;
    public static final ModConfigSpec.IntValue NOTIFICATION_JOIN_QUIET_TICKS;

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
        b.comment("Our own \"you comprehended something new\" notification.",
                  "",
                  "This is a FALLBACK. It is off by default and is meant for players who run no",
                  "pickup-notification mod at all. If you have one installed, leave this off and let",
                  "that mod mark new items instead.",
                  "",
                  "None of these settings can change WHAT gets discovered -- only whether you are told.")
                .push("notifications");

        NOTIFY_ON_NEW_COMPREHENSION = b
                .comment("Master switch. If true, show a notice when you comprehend something you had never",
                         "comprehended before. Ordinary pickups of items you already know show nothing.")
                .define("notifyOnNewComprehension", false);

        NOTIFICATION_STYLE = b
                .comment("How the notice is drawn:",
                         "  TOAST      - a vanilla toast top-right, with the item's icon. At most one of ours",
                         "               exists at a time; a burst folds into it instead of queueing.",
                         "  ACTION_BAR - one line above the hotbar. No icon, but never covers anything.",
                         "  CHAT       - a chat line, so it stays in the log.")
                .defineEnum("notificationStyle", NotificationStyle.TOAST);

        NOTIFICATION_COALESCE_TICKS = b
                .comment("Wait this many ticks (20 = 1s) of no new comprehension before showing the notice, so",
                         "one burst becomes one notice. A stack landing across several slots, emptying a",
                         "shulker box, and a cascade of unlocks all arrive as separate deltas.",
                         "0 shows a notice the very next tick.")
                .defineInRange("notificationCoalesceTicks", 20, 0, 200);

        NOTIFICATION_MIN_INTERVAL_TICKS = b
                .comment("Minimum gap between two notices, in ticks. Anything comprehended during the gap is",
                         "held back and folded into the next notice rather than dropped. 0 disables the limit.")
                .defineInRange("notificationMinIntervalTicks", 100, 0, 1200);

        NOTIFICATION_BULK_THRESHOLD = b
                .comment("A single notice covering this many items or more is treated as a sweep rather than",
                         "news, and is dropped entirely -- '+1300 new items' tells you nothing. Reached by",
                         "/gatedjei discoverall, by installing the mod into an established save, or by another",
                         "mod filling your inventory in one go. Set 0 to always notify, however large.")
                .defineInRange("notificationBulkThreshold", 32, 0, 4096);

        NOTIFICATION_MAX_ICONS = b
                .comment("How many item icons one notice cycles through. The count it reports is still exact.")
                .defineInRange("notificationMaxIcons", 6, 1, 9);

        NOTIFICATION_JOIN_QUIET_TICKS = b
                .comment("Stay silent for this many ticks after joining a world. The join sweep re-derives",
                         "discovery from an inventory you have been carrying all along, which is bookkeeping,",
                         "not discovery. Set 0 to be told about the join sweep too.")
                .defineInRange("notificationJoinQuietTicks", 100, 0, 1200);

        b.pop();
        b.comment("Integration with pick-up notification mods.",
                  "",
                  "These mods announce 'you picked up 3 dirt'. This mod knows something different and",
                  "much rarer: 'this is the first dirt you have ever held'. Rather than add a second",
                  "notification saying nearly the same thing, we mark THEIR row when the item is a",
                  "first-ever sighting. All of this does nothing at all unless the mod in question is",
                  "installed, and discovery itself never depends on any of it.").push("compat");

        DECORATE_EXTERNAL_PICKUP_DISPLAYS = b
                .comment("Master switch for all pick-up-display integrations. Set false to leave every",
                         "supported mod's display exactly as it ships.")
                .define("decorateExternalPickupDisplays", true);

        DECORATE_PICKUP_NOTIFIER = b
                .comment("Mark newly comprehended items in Pick Up Notifier's display (mod id 'pickupnotifier').",
                         "On by default: the hook is one method on a class whose 1.21.1 build has not changed",
                         "functionally since April 2025, and if it ever stops matching you lose the marker,",
                         "not the game.",
                         "Note that Pick Up Notifier only shows GROUND pickups, so a first-ever sighting that",
                         "arrives by crafting, a chest, a trade or /give has no row there to mark.")
                .define("decoratePickupNotifier", true);

        DECORATE_LOOT_JOURNAL = b
                .comment("Mark newly comprehended items in Loot Journal's display (mod id 'loot_journal').",
                         "OFF by default, on purpose. Loot Journal has no API, and its maintainer has stated",
                         "that the internals this has to use are not API, are not recommended, and will be",
                         "removed in a planned Kotlin migration that will introduce a real API instead.",
                         "Turning this on is fine today and will stop working at some announced point.")
                .define("decorateLootJournal", false);

        NOTIFY_ALONGSIDE_EXTERNAL_DISPLAYS = b
                .comment("Whether OUR notification (see [notifications]) still fires when a supported pickup",
                         "display is installed and being decorated.",
                         "Default false: the same first-ever sighting is announced once, not twice. The other",
                         "mod's row carries the marker and ours stays quiet. Set true if you want both.",
                         "Has no effect unless you have also turned our notification on, which is off by default.")
                .define("notifyAlongsideExternalDisplays", false);

        NOVELTY_GLYPH = b
                .comment("Prefixed to the item name in a decorated row. Set to an empty string for colour only.",
                         "Must be a character your font has; the default is in Minecraft's default font.")
                .define("noveltyGlyph", "\u2726");

        NOVELTY_COLOR = b
                .comment("Colour of the marker, as a decimal RGB value (0xRRGGBB written in decimal).",
                         "Default 5636095 = 0x55FFFF, cyan: distinct from every vanilla rarity colour.",
                         "The item's own name keeps its own colour if it specifies one, so rarity still reads.")
                .defineInRange("noveltyColor", 0x55FFFF, 0x000000, 0xFFFFFF);

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

    /**
     * @see #orDefault(ModConfigSpec.BooleanValue, boolean)
     *
     * <p>The catch-all for value types with no dedicated subclass (notably {@code String}). The more
     * specific overloads above still win for the types they name, so existing call sites are
     * unaffected.
     */
    public static <T> T orDefault(ModConfigSpec.ConfigValue<T> value, T fallback) {
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
