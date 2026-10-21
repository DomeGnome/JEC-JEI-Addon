package com.gatedjei.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Makes a compat mixin config apply only when the mod it targets is actually installed.
 *
 * <h2>Why a plugin, when {@code @Pseudo} already degrades quietly</h2>
 *
 * <p>{@code @Pseudo} on a soft target is the first line of defence and does most of the work: Mixin
 * skips a soft target it cannot find instead of erroring. This plugin is the second, and it is worth
 * having for two reasons. It makes the gate <b>explicit and auditable</b> — "this config is for
 * {@code pickupnotifier}" is stated once, in one place, rather than inferred from the absence of a
 * crash. And it means our mixin classes are never even considered, so a future change that
 * accidentally introduces a hard third-party reference cannot produce a {@code NoClassDefFoundError}
 * on a machine without that mod.
 *
 * <p>NeoForge 21.1 has no declarative alternative: {@code [[mixins]]} in {@code neoforge.mods.toml}
 * accepts only a {@code config} key on this version — verified by searching FML loader 4.0.34, which
 * contains no {@code requiredMods} string anywhere. A plugin is the mechanism available.
 *
 * <h2>Why {@code LoadingModList} and not {@code ModList}</h2>
 *
 * <p>Mixin configs are loaded during FML's loading phase, before mods are constructed, so
 * {@code ModList.get()} is still null. {@code LoadingModList} is the loading-phase equivalent and is
 * populated by the time mixin configs are read. {@link ExternalDisplay} uses the ordinary
 * {@code ModList} because it is only ever consulted from running game code.
 *
 * <h2>Failing open</h2>
 *
 * <p>If the lookup throws — a loader refactor, a timing change — this returns {@code true} and lets
 * Mixin's own handling take over. That path is already configured to degrade: the configs are
 * {@code required: false} with {@code injectors.defaultRequire: 0}, and the mixins are
 * {@code @Pseudo}. Failing closed would be the worse bug, because it would silently disable a
 * working integration and leave nothing in the log to explain why.
 */
public abstract class CompatMixinPlugin implements IMixinConfigPlugin {

    /** The mod id this config's mixins target, from {@link CompatModIds}. */
    protected abstract String requiredModId();

    @Override
    public void onLoad(String mixinPackage) {
        // Nothing to set up. Deliberately does not log: this runs for every launch, installed or
        // not, and a line per absent optional integration is noise in everyone's log.
    }

    @Override
    public String getRefMapperConfig() {
        // NeoForge 1.21.1 runs on official Mojang names, so there is no refmap to apply -- and
        // these targets are third-party classes, which are never remapped in any case.
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isModPresent(requiredModId());
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null; // the config's own lists are complete; nothing to add dynamically
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        // no-op
    }

    private static boolean isModPresent(String modId) {
        try {
            return net.neoforged.fml.loading.LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            return true; // see "Failing open" above
        }
    }
}
