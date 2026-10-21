package com.gatedjei.mixin;

import com.gatedjei.hook.MixinProbe;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proof-of-wiring mixin: the universal choke point through which almost every ItemStack that
 * enters the local player's inventory passes.
 *
 * <p>{@code Inventory#setItem(int, ItemStack)} is the only {@code setItem} overload on
 * {@code net.minecraft.world.entity.player.Inventory} in 1.21.1 (verified against the decompiled
 * NeoForge-patched source, line 385), so the explicit descriptor below is belt-and-braces rather
 * than strictly required.
 */
@Mixin(value = Inventory.class, remap = false)
public abstract class InventoryMixin {
    @Inject(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void gatedjei$onSetItem(int index, ItemStack stack, CallbackInfo ci) {
        MixinProbe.onInventorySetItem();
    }
}
