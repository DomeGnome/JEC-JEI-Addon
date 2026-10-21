package com.gatedjei;

import com.gatedjei.recipe.SatisfactionRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier A: the real gating rule, exercised with no Minecraft types in sight.
 *
 * <p>These call {@link SatisfactionRule} directly — the same code path {@code jei.RecipeGate} uses
 * in production. An earlier version of this file restated the rule locally, which meant it could
 * pass while the mod was broken; it now guards the shipping logic.
 *
 * <p>Keys here are plain strings. Production passes {@code Item} instances through the same generic
 * entry points, so the behaviour under test is identical.
 */
class SatisfactionRuleTest {

    /** Default production config: gate on inputs only, hide what we cannot read. */
    private static final SatisfactionRule.Options DEFAULTS =
            new SatisfactionRule.Options(false, false, false);

    private static boolean reveal(List<Set<String>> inputs, Set<String> outputs,
                                  Set<String> discovered, SatisfactionRule.Options opts) {
        return SatisfactionRule.shouldReveal(true, inputs, outputs, discovered::contains, opts);
    }

    // ---- the core any-of / all-groups rule ----

    @Test
    void recipeWithNoInputsOrOutputsIsRevealed() {
        // Fail open: we have no basis to judge it, so we must not hide it forever.
        assertTrue(reveal(List.of(), Set.of(), Set.of(), DEFAULTS));
    }

    @Test
    void allSlotsKnownIsSatisfied() {
        var recipe = List.of(Set.of("minecraft:oak_log"), Set.of("minecraft:stick"));
        assertTrue(reveal(recipe, Set.of(), Set.of("minecraft:oak_log", "minecraft:stick", "minecraft:dirt"), DEFAULTS));
    }

    @Test
    void oneUnknownSlotBlocks() {
        var recipe = List.of(Set.of("minecraft:oak_log"), Set.of("minecraft:stick"));
        assertFalse(reveal(recipe, Set.of(), Set.of("minecraft:oak_log"), DEFAULTS));
    }

    @Test
    void anyAlternativeInATagSlotSuffices() {
        var recipe = List.of(Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log"));
        assertTrue(reveal(recipe, Set.of(), Set.of("minecraft:birch_log"), DEFAULTS));
    }

    @Test
    void emptySlotIsIgnoredNotBlocking() {
        var recipe = List.of(Set.<String>of(), Set.of("minecraft:stick"));
        assertTrue(reveal(recipe, Set.of(), Set.of("minecraft:stick"), DEFAULTS));
    }

    // ---- requireOutputsDiscovered ----

    @Test
    void outputsAreIgnoredByDefault() {
        var recipe = List.of(Set.of("minecraft:oak_log"));
        assertTrue(reveal(recipe, Set.of("minecraft:oak_planks"), Set.of("minecraft:oak_log"), DEFAULTS));
    }

    @Test
    void outputsBlockWhenRequired() {
        var opts = new SatisfactionRule.Options(false, true, false);
        var recipe = List.of(Set.of("minecraft:oak_log"));
        assertFalse(reveal(recipe, Set.of("minecraft:oak_planks"), Set.of("minecraft:oak_log"), opts));
        assertTrue(reveal(recipe, Set.of("minecraft:oak_planks"),
                Set.of("minecraft:oak_log", "minecraft:oak_planks"), opts));
    }

    // ---- unresolved recipes ----

    @Test
    void unresolvedRecipeFollowsPolicyAndIgnoresDiscovery() {
        var hide = new SatisfactionRule.Options(false, false, false);
        var show = new SatisfactionRule.Options(false, false, true);
        // Inputs are unreadable, so discovery is irrelevant either way.
        assertFalse(SatisfactionRule.shouldReveal(false, List.of(), Set.of(), k -> true, hide));
        assertTrue(SatisfactionRule.shouldReveal(false, List.of(), Set.of(), k -> false, show));
    }

    // ---- revealAll overrides everything ----

    @Test
    void revealAllShowsEvenAnUnsatisfiedUnresolvedRecipe() {
        var opts = new SatisfactionRule.Options(true, true, false);
        assertTrue(SatisfactionRule.shouldReveal(
                false, List.of(Set.of("minecraft:oak_log")), Set.of("minecraft:oak_planks"), k -> false, opts));
    }

    // ---- the README's advertised loop, as an executable contract ----

    @Test
    void theLogToPlanksToSticksProgression() {
        var planks = List.of(Set.of("minecraft:oak_log"));
        var sticks = List.of(Set.of("minecraft:oak_planks"));

        Set<String> known = new java.util.HashSet<>();
        assertFalse(reveal(planks, Set.of(), known, DEFAULTS), "nothing known: planks hidden");
        assertFalse(reveal(sticks, Set.of(), known, DEFAULTS), "nothing known: sticks hidden");

        known.add("minecraft:oak_log");
        assertTrue(reveal(planks, Set.of(), known, DEFAULTS), "log known: planks revealed");
        assertFalse(reveal(sticks, Set.of(), known, DEFAULTS), "log known: sticks still hidden");

        known.add("minecraft:oak_planks");
        assertTrue(reveal(sticks, Set.of(), known, DEFAULTS), "planks known: sticks revealed");
    }
}
