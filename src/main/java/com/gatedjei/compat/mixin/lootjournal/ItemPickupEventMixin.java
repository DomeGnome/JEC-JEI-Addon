package com.gatedjei.compat.mixin.lootjournal;

import com.gatedjei.compat.ExternalDisplay;
import com.gatedjei.compat.PickupEntryDecorator;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a Loot Journal row whose item the player has never held before.
 *
 * <h2>Off by default, and deliberately disposable</h2>
 *
 * <p>Loot Journal (Obscuria) has no API. Its licence explicitly permits mixins and compatibility
 * layers, so licence is not the obstacle — maintenance is. On 2026-09-18, in issue #5 of its
 * repository, the maintainer stated that the entry points available today are not API, that he does
 * not recommend using them, that they will be removed in a planned Kotlin migration, and that a
 * proper stable API will be exposed as part of that migration. A Kotlin rewrite is precisely the
 * kind of change that renames every class a {@code @Pseudo} mixin names by string.
 *
 * <p>So this integration ships switched <b>off</b> ({@code decorateLootJournal = false}) and should
 * be treated as having an announced expiry date. It is here because it is the same two-callback
 * shape as the Pick Up Notifier one and costs nothing to carry, not because it is a good long-term
 * bet. When the announced API lands, delete this file and use it.
 *
 * <p>Loot Journal's only real extension surface is a resource-pack theme system whose bindings live
 * inside a whole theme file with no inheritance or merging, so an integration through it would have
 * to make the player abandon whichever theme they chose. That is why it is not used.
 *
 * <h2>Verified target</h2>
 *
 * <p>Read with {@code javap} out of {@code loot_journal-neoforge-1.21.1-6.2.2.jar}:
 * <pre>
 *   public dev.obscuria.lootjournal.client.events.ItemPickupEvent(AbstractClientPlayer, ItemStack)
 *     descriptor: (Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/item/ItemStack;)V
 *   public net.minecraft.network.chat.Component displayName()
 *     descriptor: ()Lnet/minecraft/network/chat/Component;
 * </pre>
 *
 * <p>As with the other compat mixin, the annotation processor cannot verify a {@code @Pseudo} string
 * target, so "this applies" is unverified offline.
 *
 * <h2>Why the player is checked</h2>
 *
 * <p>Loot Journal also displays nearby players' pickups. Marking one of those would both claim the
 * local player has never held something they may well own, and consume the novelty so their own
 * first pickup went unmarked later. The constructor hands us the player, so the check is an identity
 * comparison.
 */
@Pseudo
@Mixin(targets = "dev.obscuria.lootjournal.client.events.ItemPickupEvent", remap = false)
public abstract class ItemPickupEventMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"), remap = false)
    private void gatedjei$recordNovelty(AbstractClientPlayer player, ItemStack stack, CallbackInfo ci) {
        PickupEntryDecorator.markIfNovelFor(this, player, stack, ExternalDisplay.LOOT_JOURNAL);
    }

    @Inject(method = "displayName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void gatedjei$decorateDisplayName(CallbackInfoReturnable<Component> cir) {
        Component decorated = PickupEntryDecorator.decorate(this, cir.getReturnValue(),
                ExternalDisplay.LOOT_JOURNAL);
        if (decorated != cir.getReturnValue()) {
            cir.setReturnValue(decorated);
        }
    }
}
