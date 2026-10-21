package com.gatedjei.mixin;

import com.gatedjei.hook.DiscoveryHooks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes equipment-slot writes.
 *
 * <p>This is the hook the fluid feature depends on. {@code Player#setItemSlot} writes
 * {@code inventory.items}, {@code inventory.offhand} and {@code inventory.armor} directly, so none
 * of the other hooks see it — and it is the sole client-side path for filling your <em>last</em>
 * bucket or bottle, milking a cow, bucketing a fish, stew-to-bowl, potion-to-bottle, every cauldron
 * interaction, and the offhand swap.
 */
@Mixin(value = Player.class, remap = false)
public abstract class PlayerMixin {

    @Inject(method = "setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"))
    private void gatedjei$onSetItemSlot(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        DiscoveryHooks.onEquipmentSlot((Player) (Object) this, stack);
    }
}
