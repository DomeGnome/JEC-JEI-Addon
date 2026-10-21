package com.gatedjei.compat;

import com.gatedjei.Config;
import com.gatedjei.GatedJei;
import com.gatedjei.discovery.DiscoveryState;
import com.gatedjei.discovery.SubtypeKeys;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "is this stack the first of its kind this save has ever held?" <em>at the instant of the
 * event</em>, and hands that answer out at most once.
 *
 * <h2>Why {@code DiscoveryState} cannot be asked directly</h2>
 *
 * <p>{@link DiscoveryState}'s delta listeners are the right signal for JEI and for our own batched
 * notification: they fire once per kind per save, and neither cares exactly when. They are the wrong
 * signal for decorating a third-party row, because they fire too late. Both supported displays build
 * their entry synchronously inside the pickup packet handler and compute the row's label right
 * there, while {@code ObservationQueue} does not drain until the end of that client tick. At label
 * time the item is not in the discovery set and no delta has fired — so the only way to mark a
 * first-ever pickup, which is the exact case worth marking, is to be able to answer before discovery
 * is applied.
 *
 * <p>"Not in the discovery set yet" is most of that answer. What it misses is the duplicate: ground
 * items arrive as separate entities, so picking up two dirt in one tick builds two rows, and both
 * would see an undiscovered item and both would be marked "first ever". This class exists for that
 * — it turns the question into a one-shot claim.
 *
 * <h2>How a claim is decided</h2>
 *
 * <ol>
 *   <li>Has a presenter already taken this kind? That is {@code claimed}, which exists purely to
 *       bridge the window between the event and the drain.</li>
 *   <li>Does {@code DiscoveryState} already hold it? Then it was comprehended in an earlier tick or
 *       an earlier session, and is not news.</li>
 * </ol>
 *
 * <p>There is deliberately no listener on {@code DiscoveryState}. Once the drain has run, step 2
 * answers by itself — which is also why claims can be pruned on a short timer and why the map is
 * empty in steady play.
 *
 * <p>Free of any {@code net.minecraft.client} type, so the claim rule is testable without a game
 * client. Nothing here throws: it is called from inside another mod's constructor.
 */
public final class NoveltySightings {

    /**
     * How long a claim blocks a second claim of the same kind.
     *
     * <p>It only has to outlast the gap between the event and the next drain, which is one client
     * tick; ten seconds is slack for a stalled drain. Longer would be actively wrong — once the kind
     * is in the discovery set, a repeat pickup must read as "not news", and that answer has to come
     * from {@code DiscoveryState} rather than from a lingering entry here.
     */
    static final int CLAIM_TTL_TICKS = 200;

    private static final NoveltySightings INSTANCE = new NoveltySightings(CLAIM_TTL_TICKS);

    public static NoveltySightings get() {
        return INSTANCE;
    }

    /** Claimed kind -> the tick it was claimed. A key is an {@link Item} or a variant-key String. */
    private final Map<Object, Integer> claimed = new ConcurrentHashMap<>();

    private final int ttlTicks;

    /** Client ticks since the last clear. Only the client thread advances it. */
    private volatile int now;

    private boolean warnedFailure;

    /** Visible for testing with a short TTL. */
    NoveltySightings(int ttlTicks) {
        this.ttlTicks = ttlTicks;
    }

    /**
     * Take this stack's first-ever sighting, if it is one and nobody else has taken it.
     *
     * @return {@code true} for exactly one caller, and only if this save has never held the kind
     */
    public boolean claim(ItemStack stack) {
        try {
            // No save loaded means discovery is not being recorded, so nothing can be "first": a
            // claim here would announce something that is then never persisted.
            if (DiscoveryState.get().loadedKey() == null) {
                return false;
            }
            Object key = keyOf(stack);
            if (key == null || alreadyComprehended(key)) {
                return false;
            }
            return claimed.putIfAbsent(key, now) == null;
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** Advance the clock and drop expired claims. Called once per client tick. */
    public void tick() {
        now++;
        if (claimed.isEmpty()) {
            return; // the steady-state path: one check and nothing else
        }
        int cutoff = now - ttlTicks;
        for (Iterator<Map.Entry<Object, Integer>> it = claimed.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= cutoff) {
                it.remove();
            }
        }
    }

    /** Forget every claim. Called at session and save-key boundaries. */
    public void clear() {
        claimed.clear();
        now = 0;
    }

    public int pendingClaims() {
        return claimed.size();
    }

    /**
     * The kind a stack counts as, matching what the gate actually hides.
     *
     * <p>In granular mode a Sharpness book and a Mending book are different kinds, because JEI lists
     * them separately and the gate reveals them separately — so "first time you have held this" has
     * to mean the variant. Otherwise the kind is the item.
     *
     * @return an {@link Item} or a variant-key {@link String}; {@code null} for an empty stack
     */
    static Object keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (Config.orDefault(Config.GRANULAR_SUBTYPE_DISCOVERY, true)
                && SubtypeKeys.isSubtypeVariant(stack)) {
            return SubtypeKeys.variantKey(stack);
        }
        return stack.getItem();
    }

    private static boolean alreadyComprehended(Object key) {
        if (key instanceof Item item) {
            return DiscoveryState.get().isDiscovered(item);
        }
        return DiscoveryState.get().isVariantDiscovered((String) key);
    }

    private void warnOnce(Throwable t) {
        if (!warnedFailure) {
            warnedFailure = true;
            GatedJei.LOGGER.warn("Novelty claim failed, treating as 'not new': {}", t.toString());
        }
    }
}
