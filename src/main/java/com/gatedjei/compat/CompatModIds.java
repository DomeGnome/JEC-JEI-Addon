package com.gatedjei.compat;

/**
 * Mod ids of the third-party pickup displays this mod knows about.
 *
 * <p>Deliberately constants-only with <b>no imports</b>. A {@code IMixinConfigPlugin} is classloaded
 * during mixin config loading, long before FML has a {@code ModList} or a loaded config, and
 * anything it touches is classloaded with it. A class with no dependencies is safe to touch at any
 * point in startup; a class that happened to reference {@code Config} or {@code ModList} would not
 * be.
 *
 * <p>Every id here was read verbatim out of the shipped jar's {@code META-INF/neoforge.mods.toml}
 * (or {@code fabric.mod.json}), not from a wiki or a project page.
 */
public final class CompatModIds {

    /**
     * Pick Up Notifier by Fuzs. MPL-2.0. Client-required, server-optional.
     *
     * <p>Verified in {@code PickUpNotifier-v21.1.1-1.21.1-NeoForge.jar}. This is the one display we
     * integrate with: see {@link com.gatedjei.compat.mixin.pickupnotifier.ItemDisplayEntryMixin}.
     */
    public static final String PICKUP_NOTIFIER = "pickupnotifier";

    /**
     * Loot Journal: Pickup Notifier by Obscuria. Obscuria Ecosystem/Modding Licence (not OSI; it
     * explicitly permits mixins and compatibility layers). Client-side, joins vanilla servers.
     *
     * <p>Verified in {@code loot_journal-neoforge-1.21.1-6.2.2.jar}. Integrated, but
     * <b>off by default</b> — the maintainer has stated on the record (issue #5, 2026-09-18) that
     * the internals any decoration must use today are not API, are not recommended, and will be
     * removed in a planned Kotlin migration that will also introduce a real API. Treat the
     * integration as disposable and prefer the real API when it lands.
     */
    public static final String LOOT_JOURNAL = "loot_journal";

    /**
     * Pickup Notifications by Roundaround. MIT. <b>Not integrated, and no code ships for it.</b>
     *
     * <p>Recorded here only so nobody re-investigates. There is no NeoForge build of this mod for
     * Minecraft 1.21.1 and there never has been: its only 1.21.1-compatible release, {@code
     * 1.7.1+1.21}, is Fabric/Quilt only and its jar carries a {@code fabric.mod.json} and an
     * intermediary refmap with no {@code mods.toml} at all. NeoForge support begins at Minecraft
     * 26.1. So {@code ModList.isLoaded} can never return true for it on our target, and a mixin
     * against its classes would be permanently dead code.
     *
     * <p>It is also the wrong architectural partner even where it exists: it declares
     * {@code "environment": "*"} and detects pickups by server-side inventory diffing pushed to the
     * client over its own payload, so on a vanilla server — the case this mod is built for — its
     * display is empty and there would be nothing to decorate.
     */
    public static final String UNSUPPORTED_PICKUP_NOTIFICATIONS = "pickupnotifications";

    private CompatModIds() {}
}
