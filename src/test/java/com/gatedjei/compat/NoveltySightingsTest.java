package com.gatedjei.compat;

import com.gatedjei.discovery.DiscoveryState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rule, which is the part of the integration that can actually be tested offline.
 *
 * <p>Whether the mixins apply cannot be: there is no display to run the game on, and a
 * {@code @Pseudo} string target is invisible to the Mixin annotation processor. What <em>can</em> be
 * checked is the decision the mixins delegate to — and that is where the interesting bugs are,
 * because it has to be right in a window where the discovery set has not been updated yet.
 */
class NoveltySightingsTest {

    private NoveltySightings sightings;

    @BeforeEach
    void setUp() {
        // A loaded save key is a precondition for any claim; without one the ledger refuses.
        DiscoveryState.get().replaceLoaded("test", Set.of(), Set.of(), Set.of());
        sightings = new NoveltySightings(5);
    }

    @Test
    void claimsAnUndiscoveredItemExactlyOnce() {
        assertTrue(sightings.claim(new ItemStack(Items.DIRT)),
                "a kind the save has never held is a first-ever sighting");
        assertFalse(sightings.claim(new ItemStack(Items.DIRT)),
                "two item entities picked up in one tick must not both be marked 'first ever'");
    }

    @Test
    void refusesAKindAlreadyInTheDiscoverySet() {
        DiscoveryState.get().discover(Items.DIRT);
        assertFalse(sightings.claim(new ItemStack(Items.DIRT)),
                "an item comprehended earlier, or in a previous session, is not news");
    }

    @Test
    void differentKindsDoNotBlockEachOther() {
        assertTrue(sightings.claim(new ItemStack(Items.DIRT)));
        assertTrue(sightings.claim(new ItemStack(Items.STONE)));
    }

    @Test
    void refusesAnEmptyStack() {
        assertFalse(sightings.claim(ItemStack.EMPTY));
        assertFalse(sightings.claim(null));
    }

    @Test
    void refusesEverythingWithNoSaveLoaded() {
        DiscoveryState.get().unload();
        assertFalse(sightings.claim(new ItemStack(Items.DIRT)),
                "with no save loaded the discovery would never be persisted, so it is not a sighting");
    }

    @Test
    void anExpiredClaimDoesNotBlockForever() {
        assertTrue(sightings.claim(new ItemStack(Items.DIRT)));
        for (int i = 0; i < 6; i++) {
            sightings.tick();
        }
        // The claim has aged out of the bridging window. The item is still not in the discovery set
        // -- in practice that means the drain never ran -- so a fresh sighting is allowed rather
        // than the kind being silently blacklisted for the rest of the session.
        assertTrue(sightings.claim(new ItemStack(Items.DIRT)));
    }

    @Test
    void expiryDoesNotResurrectAnAlreadyComprehendedKind() {
        assertTrue(sightings.claim(new ItemStack(Items.DIRT)));
        DiscoveryState.get().discover(Items.DIRT); // what the next drain does
        for (int i = 0; i < 6; i++) {
            sightings.tick();
        }
        assertFalse(sightings.claim(new ItemStack(Items.DIRT)),
                "once the drain has applied the discovery, the discovery set is the authority");
    }
}
