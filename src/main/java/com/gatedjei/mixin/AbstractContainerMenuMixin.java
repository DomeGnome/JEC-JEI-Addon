package com.gatedjei.mixin;

import com.gatedjei.hook.DiscoveryHooks;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Observes the cursor stack, which never passes through {@code Inventory}.
 */
@Mixin(value = AbstractContainerMenu.class, remap = false)
public abstract class AbstractContainerMenuMixin {

    /**
     * The mouse-held stack. HEAD rather than RETURN because the stack handed in is frequently the
     * already-drained object returned by {@code Slot#safeInsert}.
     */
    @Inject(method = "setCarried(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void gatedjei$onSetCarried(ItemStack stack, CallbackInfo ci) {
        DiscoveryHooks.onCursor((AbstractContainerMenu) (Object) this, stack);
    }

    /**
     * A bulk sync, including the inventory the server sends on world join.
     *
     * <p>Only the carried stack needs taking: the item list arrives via {@code Slot#set} and so is
     * already seen by the {@code Inventory} hook. The carried stack, by contrast, is assigned to the
     * field directly here and never announced again, making this the only chance to observe it.
     */
    @Inject(method = "initializeContents(ILjava/util/List;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"))
    private void gatedjei$onInitializeContents(int stateId, List<ItemStack> items, ItemStack carried,
                                               CallbackInfo ci) {
        DiscoveryHooks.onBulkSync((AbstractContainerMenu) (Object) this, carried);
    }
}
