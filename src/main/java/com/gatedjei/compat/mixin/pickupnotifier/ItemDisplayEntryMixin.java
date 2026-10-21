package com.gatedjei.compat.mixin.pickupnotifier;

import com.gatedjei.compat.ExternalDisplay;
import com.gatedjei.compat.PickupEntryDecorator;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a Pick Up Notifier row whose item the player has never held before.
 *
 * <p>Pick Up Notifier (Fuzs, MPL-2.0) has no API of any kind — no {@code api} package, no
 * interfaces, no services, no events of its own; the only data-driven surface is an item blacklist
 * that can suppress rows but not style them. Decorating it therefore means touching its internals.
 * This is the smallest hook its internals offer: one constructor and one no-argument method
 * returning a vanilla {@link Component}.
 *
 * <p>No code from Pick Up Notifier is reproduced here. MPL-2.0 is file-level copyleft and a mixin is
 * not a derivative of the source text it injects into, so nothing in this file carries an obligation
 * from their licence.
 *
 * <h2>Verified target</h2>
 *
 * <p>Both members were read with {@code javap} out of the shipped
 * {@code PickUpNotifier-v21.1.1-1.21.1-NeoForge.jar}, which is not obfuscated or remapped:
 * <pre>
 *   public fuzs.pickupnotifier.client.gui.entry.ItemDisplayEntry(ItemStack, int)
 *     descriptor: (Lnet/minecraft/world/item/ItemStack;I)V
 *   protected net.minecraft.network.chat.Component getEntryName()
 *     descriptor: ()Lnet/minecraft/network/chat/Component;
 * </pre>
 *
 * <h2>What cannot be verified offline</h2>
 *
 * <p>That the injections <b>apply</b>. {@code @Pseudo} with a string target is invisible to the
 * Mixin annotation processor, so this project's usual compile-time target check does not cover this
 * file — the build cannot tell you the target moved. Run {@code ./gradlew verifyCompatTargets
 * -PcompatJars=<path to the jar>} to check the signatures above against a real jar, and see
 * {@code /gatedjei compat} in game. The 1.21.1 branch has had no functional commit since April 2025
 * and is backports-only, so the practical drift risk on this version is low; the plugin gate and
 * {@code defaultRequire: 0} mean a miss costs the marker, not the game.
 *
 * <h2>Known gap, by the host's design</h2>
 *
 * <p>Pick Up Notifier's client path reads {@code ClientboundTakeItemEntityPacket}, so it only ever
 * shows ground pickups. A first-ever sighting that arrives by crafting, a container transfer, a
 * trade, {@code /give} or {@code /gatedjei discoverheld} produces no row here to decorate. Those are
 * covered by this mod's own hooks, and are the case the default-off fallback notification exists
 * for. Discovery itself never depends on this file.
 */
@Pseudo
@Mixin(targets = "fuzs.pickupnotifier.client.gui.entry.ItemDisplayEntry", remap = false)
public abstract class ItemDisplayEntryMixin {

    /**
     * Decide novelty at construction, which is inside the pickup packet handler and therefore the
     * one moment the answer is still available — our observation queue has not drained yet, so the
     * item is not in the discovery set and the sighting can be claimed.
     *
     * <p>The stack arrives as a parameter, so no {@code @Shadow} of the host's private field is
     * needed and this mixin names no third-party type at all.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/item/ItemStack;I)V", at = @At("RETURN"), remap = false)
    private void gatedjei$recordNovelty(ItemStack stack, int amount, CallbackInfo ci) {
        PickupEntryDecorator.markIfNovel(this, stack, ExternalDisplay.PICKUP_NOTIFIER);
    }

    /**
     * Prefix the marker onto the row's name.
     *
     * <p>{@code RETURN} + {@code cancellable} rather than MixinExtras' {@code @ModifyReturnValue}:
     * the behaviour is identical for a single decorator, and it keeps this file dependent on nothing
     * but the Mixin subsystem itself, which matters in a class that is only loaded when a third-party
     * mod is present.
     *
     * <p>The host caches this value per row and recomputes it after a merge, which is harmless here
     * because the verdict was frozen at construction rather than re-derived now.
     */
    @Inject(method = "getEntryName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void gatedjei$decorateEntryName(CallbackInfoReturnable<Component> cir) {
        Component decorated = PickupEntryDecorator.decorate(this, cir.getReturnValue(),
                ExternalDisplay.PICKUP_NOTIFIER);
        if (decorated != cir.getReturnValue()) {
            cir.setReturnValue(decorated);
        }
    }
}
