package com.gatedjei.notify;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batching rule for our own notification.
 *
 * <p>This is the part of the feature that can actually be verified without a display: how many
 * notices a burst of discovery deltas turns into, and what each one says. The drawing itself
 * ({@link ComprehensionToast}) needs a GPU and is not covered here.
 */
class NoticeFeedTest {

    private static final NoticeFeed.Settings FAST =
            new NoticeFeed.Settings(3, 0, 0, 6); // coalesce 3, no rate limit, no bulk drop

    /** Collects notices so a test can count them. */
    private static final class Recorder implements NoticeFeed.Sink {
        final List<NoticeFeed.Notice> notices = new ArrayList<>();

        @Override
        public void present(NoticeFeed.Notice notice) {
            notices.add(notice);
        }
    }

    private static NoticeFeed.Notice tickUntilNotice(NoticeFeed feed, NoticeFeed.Settings settings, int maxTicks) {
        Recorder recorder = new Recorder();
        for (int i = 0; i < maxTicks; i++) {
            feed.tick(settings, recorder);
            if (!recorder.notices.isEmpty()) {
                return recorder.notices.get(0);
            }
        }
        return null;
    }

    @Test
    void aBurstSpreadOverTicksBecomesOneNotice() {
        NoticeFeed feed = new NoticeFeed();
        Recorder recorder = new Recorder();

        // Three deltas on three consecutive ticks -- what a stack landing across slots looks like.
        feed.offer(Set.of(Items.OAK_LOG));
        feed.tick(FAST, recorder);
        feed.offer(Set.of(Items.OAK_PLANKS));
        feed.tick(FAST, recorder);
        feed.offer(Set.of(Items.STICK));
        for (int i = 0; i < 10; i++) {
            feed.tick(FAST, recorder);
        }

        assertEquals(1, recorder.notices.size(), "one burst must produce exactly one notice");
        assertEquals(3, recorder.notices.get(0).total());
        assertEquals(3, recorder.notices.get(0).icons().size());
    }

    @Test
    void repeatedDuplicateOffersDoNotPostponeTheNotice() {
        NoticeFeed feed = new NoticeFeed();
        Recorder recorder = new Recorder();

        // A container that keeps re-syncing the same slots offers the same items every tick. If a
        // duplicate reset the coalescing window, the notice would never be shown at all.
        for (int i = 0; i < 4; i++) {
            feed.offer(Set.of(Items.DIAMOND, Items.EMERALD));
            feed.offer(Set.of(Items.IRON_INGOT));
            feed.tick(FAST, recorder);
        }

        assertEquals(1, recorder.notices.size(), "the notice must arrive once the coalescing window passes");
        assertEquals(3, recorder.notices.get(0).total());
    }

    @Test
    void nothingIsEmittedWhileTheBurstIsStillArriving() {
        NoticeFeed feed = new NoticeFeed();
        Recorder recorder = new Recorder();
        NoticeFeed.Settings slow = new NoticeFeed.Settings(10, 0, 0, 6);

        // A genuinely growing burst: a different new item every tick, for less than coalesceTicks.
        Item[] burst = {Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT};
        for (Item item : burst) {
            feed.offer(Set.of(item));
            feed.tick(slow, recorder);
        }

        assertEquals(0, recorder.notices.size(), "a burst still in progress must not be announced");
        assertEquals(burst.length, feed.pendingCount());

        for (int i = 0; i < 15; i++) {
            feed.tick(slow, recorder);
        }
        assertEquals(1, recorder.notices.size(), "and then exactly one notice covers the whole burst");
        assertEquals(burst.length, recorder.notices.get(0).total());
    }

    @Test
    void duplicatesDoNotExtendTheWindow() {
        NoticeFeed feed = new NoticeFeed();
        assertTrue(feed.offer(Set.of(Items.DIAMOND)));
        assertFalse(feed.offer(Set.of(Items.DIAMOND)), "a re-offer of a queued item must be rejected");
        assertEquals(1, feed.pendingCount());
    }

    @Test
    void iconsAreCappedButTheCountIsExact() {
        NoticeFeed feed = new NoticeFeed();
        NoticeFeed.Settings capped = new NoticeFeed.Settings(1, 0, 0, 2);

        feed.offer(List.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT, Items.GOLD_INGOT));
        NoticeFeed.Notice notice = tickUntilNotice(feed, capped, 10);

        assertNotNull(notice);
        assertEquals(4, notice.total(), "the reported count must be exact");
        assertEquals(2, notice.icons().size(), "icons must respect maxIcons");
    }

    @Test
    void aBulkBatchIsDroppedRatherThanShown() {
        NoticeFeed feed = new NoticeFeed();
        NoticeFeed.Settings bulky = new NoticeFeed.Settings(1, 0, 3, 6);

        feed.offer(List.of(Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT));
        assertNull(tickUntilNotice(feed, bulky, 10), "a batch at the bulk threshold must not notify");
        assertEquals(0, feed.pendingCount(), "and it must not be left pending forever");
    }

    @Test
    void aBatchBelowTheBulkThresholdStillNotifies() {
        NoticeFeed feed = new NoticeFeed();
        NoticeFeed.Settings bulky = new NoticeFeed.Settings(1, 0, 3, 6);

        feed.offer(List.of(Items.DIAMOND, Items.EMERALD));
        NoticeFeed.Notice notice = tickUntilNotice(feed, bulky, 10);

        assertNotNull(notice);
        assertEquals(2, notice.total());
    }

    @Test
    void suppressionWindowDropsTheJoinSweep() {
        NoticeFeed feed = new NoticeFeed();
        Recorder recorder = new Recorder();

        feed.suppressForTicks(5);
        assertTrue(feed.isQuiet());
        assertFalse(feed.offer(Set.of(Items.DIRT)), "offers during the window are refused");

        for (int i = 0; i < 10; i++) {
            feed.tick(FAST, recorder);
        }
        assertEquals(0, recorder.notices.size());
        assertFalse(feed.isQuiet(), "the window must expire as ticks pass");

        // And normal service resumes afterwards.
        assertTrue(feed.offer(Set.of(Items.DIRT)));
        assertNotNull(tickUntilNotice(feed, FAST, 10));
    }

    @Test
    void quietBlocksNestAndRestoreNotifications() {
        NoticeFeed feed = new NoticeFeed();

        feed.beginQuiet();
        feed.beginQuiet();
        assertFalse(feed.offer(Set.of(Items.DIAMOND)));
        feed.endQuiet();
        assertFalse(feed.offer(Set.of(Items.DIAMOND)), "still quiet while the outer block is open");
        feed.endQuiet();
        assertTrue(feed.offer(Set.of(Items.DIAMOND)));
    }

    @Test
    void itemsFoundWhileRateLimitedAreHeldNotLost() {
        NoticeFeed feed = new NoticeFeed();
        NoticeFeed.Settings limited = new NoticeFeed.Settings(1, 50, 0, 6);
        Recorder recorder = new Recorder();

        feed.offer(Set.of(Items.DIAMOND));
        for (int i = 0; i < 5; i++) {
            feed.tick(limited, recorder);
        }
        assertEquals(1, recorder.notices.size(), "the first notice is not rate limited");

        feed.offer(Set.of(Items.EMERALD));
        for (int i = 0; i < 10; i++) {
            feed.tick(limited, recorder);
        }
        assertEquals(1, recorder.notices.size(), "the second notice must wait out the interval");
        assertEquals(1, feed.pendingCount(), "and the item must still be pending, not dropped");

        for (int i = 0; i < 60; i++) {
            feed.tick(limited, recorder);
        }
        assertEquals(2, recorder.notices.size());
        assertEquals(Items.EMERALD, recorder.notices.get(1).icons().get(0));
    }

    @Test
    void resetForgetsEverything() {
        NoticeFeed feed = new NoticeFeed();
        feed.offer(Set.of(Items.DIAMOND));
        feed.beginQuiet();
        feed.reset();
        assertEquals(0, feed.pendingCount());
        assertFalse(feed.isQuiet(), "reset must also clear a quiet block left open by a session");
    }

    @Test
    void anEmptyOfferDoesNothing() {
        NoticeFeed feed = new NoticeFeed();
        assertFalse(feed.offer(null));
        assertFalse(feed.offer(Set.of()));
        assertNull(tickUntilNotice(feed, FAST, 10));
    }

    @Test
    void pendingIsBounded() {
        NoticeFeed feed = new NoticeFeed();
        List<Item> everything = new ArrayList<>();
        for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            everything.add(item);
        }
        // The registry is larger than MAX_PENDING on a vanilla client, and far larger in a pack.
        feed.offer(everything);
        feed.offer(everything);
        assertTrue(feed.pendingCount() <= NoticeFeed.MAX_PENDING,
                "pending accumulation must stay bounded, got " + feed.pendingCount());
    }
}
