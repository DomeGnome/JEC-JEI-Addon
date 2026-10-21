package com.gatedjei.recipe;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The gating decision, as a pure function.
 *
 * <p>This class deliberately imports nothing from Minecraft, NeoForge or JEI. That is what lets
 * {@code src/pureTest} exercise the real rule instead of a copy of it — the rule is the part most
 * worth testing and the part that cannot be checked by looking at a screenshot.
 *
 * <p>Keys are generic: production passes {@code Item}s, tests pass {@code String}s. The rule never
 * needs to know what a key <em>is</em>, only whether it has been discovered.
 *
 * <p>Semantics (preserved exactly from the original inline implementation in
 * {@code jei.RecipeGate}):
 * <ul>
 *   <li>Every input group must contain at least one discovered key. A group is an "any-of" set, so
 *       a tag slot such as "any plank" is satisfied by discovering a single member.</li>
 *   <li>An <em>empty</em> group imposes no requirement. The resolver already filters these out, but
 *       the rule skips them defensively: treating an empty group as unsatisfiable would hide a
 *       recipe forever, and failing open is the house style for anything we cannot reason about.</li>
 *   <li>Outputs are only consulted when {@code requireOutputsDiscovered} is set.</li>
 *   <li>A recipe whose inputs could not be read is governed by {@code revealUnresolved}.</li>
 *   <li>A recipe that resolved but carries no inputs <em>and</em> no outputs is always revealed, so
 *       the mod never permanently hides something it has no basis to judge.</li>
 * </ul>
 */
public final class SatisfactionRule {
    private SatisfactionRule() {}

    /**
     * The config toggles the rule depends on, snapshotted into a plain record.
     *
     * <p>Deliberately not {@code Config} itself: that class pulls in NeoForge's {@code ModConfigSpec}
     * and would drag the whole platform onto the pure test classpath.
     *
     * @param revealAll                 debug switch: hide nothing at all
     * @param requireOutputsDiscovered  also require a recipe's outputs before revealing it
     * @param revealUnresolved          reveal (rather than hide) recipes whose inputs cannot be read
     */
    public record Options(boolean revealAll, boolean requireOutputsDiscovered, boolean revealUnresolved) {}

    /** Every input group satisfied, plus outputs when required. Does not consider config/resolution. */
    public static <K> boolean isSatisfied(List<? extends Set<K>> inputGroups,
                                          Set<K> outputs,
                                          Predicate<K> discovered,
                                          boolean requireOutputsDiscovered) {
        for (Set<K> group : inputGroups) {
            if (group.isEmpty()) {
                continue; // empty slot: nothing required
            }
            boolean any = false;
            for (K key : group) {
                if (discovered.test(key)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        if (requireOutputsDiscovered) {
            for (K key : outputs) {
                if (!discovered.test(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The full visibility decision for one recipe. */
    public static <K> boolean shouldReveal(boolean resolved,
                                           List<? extends Set<K>> inputGroups,
                                           Set<K> outputs,
                                           Predicate<K> discovered,
                                           Options options) {
        if (options.revealAll()) {
            return true;
        }
        if (!resolved) {
            return options.revealUnresolved();
        }
        if (inputGroups.isEmpty() && outputs.isEmpty()) {
            return true;
        }
        return isSatisfied(inputGroups, outputs, discovered, options.requireOutputsDiscovered());
    }
}
