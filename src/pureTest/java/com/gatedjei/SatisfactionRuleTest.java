package com.gatedjei;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier A: pure logic, zero Minecraft classes on the test classpath path that matters.
 * Mirrors RecipeGate's rule: a recipe is visible iff every input slot has at least one
 * accepted alternative that the player has discovered.
 */
class SatisfactionRuleTest {

    /** Pure re-statement of the gate rule under test. */
    static boolean isSatisfied(java.util.List<Set<String>> inputSlots, Set<String> discovered) {
        for (Set<String> alternatives : inputSlots) {
            if (alternatives.isEmpty()) continue; // empty slot = nothing to learn
            boolean any = false;
            for (String alt : alternatives) {
                if (discovered.contains(alt)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    @Test
    void emptyRecipeIsAlwaysSatisfied() {
        assertTrue(isSatisfied(java.util.List.of(), Set.of()));
    }

    @Test
    void allSlotsKnownIsSatisfied() {
        var recipe = java.util.List.of(Set.of("minecraft:oak_log"), Set.of("minecraft:stick"));
        assertTrue(isSatisfied(recipe, Set.of("minecraft:oak_log", "minecraft:stick", "minecraft:dirt")));
    }

    @Test
    void oneUnknownSlotBlocks() {
        var recipe = java.util.List.of(Set.of("minecraft:oak_log"), Set.of("minecraft:stick"));
        assertFalse(isSatisfied(recipe, Set.of("minecraft:oak_log")));
    }

    @Test
    void anyAlternativeInATagSlotSuffices() {
        var recipe = java.util.List.of(Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log"));
        assertTrue(isSatisfied(recipe, Set.of("minecraft:birch_log")));
    }

    @Test
    void emptySlotIsIgnoredNotBlocking() {
        var recipe = java.util.List.of(Set.<String>of(), Set.of("minecraft:stick"));
        assertTrue(isSatisfied(recipe, Set.of("minecraft:stick")));
    }
}
