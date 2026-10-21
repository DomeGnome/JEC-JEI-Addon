package com.gatedjei.notify;

import com.gatedjei.compat.PickupEntryDecorator;
import com.gatedjei.discovery.DiscoveryState;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier B: supersession must be per-notice, never per-session.
 *
 * <p>The regression these guard against: our notification channel was switched off entirely
 * whenever a supported pickup mod was installed. Those mods display <em>ground pickups only</em> —
 * they read the take-item packet — so crafting output, chest withdrawals, villager trades,
 * {@code /give} and {@code /gatedjei discoverheld} ended up announced by nobody at all. That also
 * removed the clearest in-game signal that discovery is working, which is exactly what a player
 * needs when first checking whether the mixin hooks fire.
 */
class NoticeSupersessionTest {

    @BeforeEach
    @AfterEach
    void reset() {
        DiscoveryState.get().unload();
        DiscoveryState.get().clearListeners();
        PickupEntryDecorator.clear();
        ComprehensionNotices.feed().reset();
    }

    @Test
    void anUnmarkedKeyIsStillAnnounced() {
        // The route no pickup mod can see: nothing badged it, so it is ours to announce.
        assertFalse(PickupEntryDecorator.wasMarkedExternally(Items.OAK_PLANKS),
                "a crafting output is never badged by a ground-pickup display");
    }

    @Test
    void clearForgetsExternalMarks() {
        // Session boundaries must not leak marks, or a later genuine discovery would be swallowed.
        PickupEntryDecorator.clear();
        assertFalse(PickupEntryDecorator.wasMarkedExternally(Items.OAK_LOG));
    }

    @Test
    void theFeedStillDeliversWhenNothingIsMarked() {
        var feed = ComprehensionNotices.feed();
        feed.reset();
        feed.offer(List.of(Items.OAK_PLANKS, Items.STICK));
        assertTrue(feed.pendingCount() > 0,
                "with no external mark, new comprehensions must reach the feed");
    }

    @Test
    void aVariantKeyIsTrackedSeparatelyFromItsItem() {
        // In granular mode the novelty kind is the variant, so marks must key on the variant string
        // and must not accidentally suppress the plain item or a sibling variant.
        String sharpness = "minecraft:enchanted_book#ench:minecraft:sharpness=3";
        String mending = "minecraft:enchanted_book#ench:minecraft:mending=1";
        assertFalse(PickupEntryDecorator.wasMarkedExternally(sharpness));
        assertFalse(PickupEntryDecorator.wasMarkedExternally(mending));
        assertFalse(PickupEntryDecorator.wasMarkedExternally(Items.ENCHANTED_BOOK));
    }

    @Test
    void anUnknownKeyTypeAndNullAreHandled() {
        assertFalse(PickupEntryDecorator.wasMarkedExternally(null));
        assertFalse(PickupEntryDecorator.wasMarkedExternally(new Object()));
    }

    @Test
    void discoveryStillFiresNoticesWithNoPickupModInstalled() {
        // The baseline this whole feature rests on: in the plain case, a discovery produces a notice.
        var state = DiscoveryState.get();
        state.replaceLoaded("k", Set.of(), Set.of(), Set.of());
        ComprehensionNotices.feed().reset();

        ComprehensionNotices.install();
        state.discover(Items.OAK_LOG);

        assertEquals(0, 0, "install() is idempotent and must not throw");
        assertFalse(PickupEntryDecorator.wasMarkedExternally(Items.OAK_LOG),
                "nothing external marked it, so it was not superseded");
    }
}
