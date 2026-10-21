package com.gatedjei.mixin;

import com.gatedjei.hook.DiscoveryHooks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes items entering the player's inventory.
 *
 * <p>All three injections are {@code @At("HEAD")} with no cancellation — the lowest-conflict
 * injector available, which matters because {@code Inventory} is a popular target for backpack and
 * sorting mods.
 *
 * <p>HEAD is also required rather than merely preferred: {@code add} calls
 * {@code stack.copyAndClear()} on its parameter, so by RETURN the stack we want to read is empty.
 */
@Mixin(value = Inventory.class, remap = false)
public abstract class InventoryMixin {

    /**
     * The universal choke point. Every slot packet, container sync, chest withdrawal, villager
     * trade, furnace output and {@code /give} ends up here via {@code Slot#set}.
     */
    @Inject(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void gatedjei$onSetItem(int index, ItemStack stack, CallbackInfo ci) {
        DiscoveryHooks.onSlotWrite((Inventory) (Object) this, stack);
    }

    /**
     * Returns boolean, so this needs {@code )Z} and a {@link CallbackInfoReturnable} — a {@code )V}
     * descriptor here would compile fine and then silently never apply at runtime.
     *
     * <p>Needed on top of {@code setItem} because the damaged-stack branch writes
     * {@code this.items.set(slot, stack.copyAndClear())} directly, bypassing {@code setItem}. It
     * also covers the {@code addResource} family, which is private and reachable only from here.
     */
    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"))
    private void gatedjei$onAdd(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        DiscoveryHooks.onAdd((Inventory) (Object) this, stack);
    }

    /**
     * Creative middle-click pick-block, which writes {@code this.items.set(...)} directly and so
     * bypasses {@code setItem}, {@code add} and {@code setCarried} alike.
     */
    @Inject(method = "setPickedItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void gatedjei$onSetPickedItem(ItemStack stack, CallbackInfo ci) {
        DiscoveryHooks.onPickBlock((Inventory) (Object) this, stack);
    }
}
