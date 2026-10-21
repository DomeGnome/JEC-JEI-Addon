package com.gatedjei.compat;

/**
 * Gate for {@code gatedjei.lootjournal.mixins.json}.
 *
 * <p>Note that this gates on <em>presence</em> only, not on the config toggle. A mixin config plugin
 * runs before FML has loaded any config file, so the toggle cannot be read here; it is enforced at
 * runtime in {@link PickupEntryDecorator} instead, which returns the host's own text untouched when
 * the integration is off. The injected code therefore exists but does nothing, which for two
 * {@code @Inject} callbacks is a cost not worth engineering away.
 *
 * @see CompatMixinPlugin
 */
public final class LootJournalMixinPlugin extends CompatMixinPlugin {
    @Override
    protected String requiredModId() {
        return CompatModIds.LOOT_JOURNAL;
    }
}
