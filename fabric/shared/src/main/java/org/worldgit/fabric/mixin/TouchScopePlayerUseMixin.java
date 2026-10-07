package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.worldgit.fabric.TouchScope;

/** 蛋、盔甲座、展示框、畫、載具、水晶、桶裝生物；scope 僅限這次物品使用。 */
@Mixin(ServerPlayerGameMode.class)
abstract class TouchScopePlayerUseMixin {
    @WrapMethod(method = "useItem")
    private InteractionResult worldgit$use(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, Operation<InteractionResult> original) {
        TouchScope.enter(TouchScope.Kind.USE);
        try { return original.call(player, level, stack, hand); }
        finally { TouchScope.exit(); }
    }
    @WrapMethod(method = "useItemOn")
    private InteractionResult worldgit$useOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        TouchScope.enter(TouchScope.Kind.USE);
        try { return original.call(player, level, stack, hand, hit); }
        finally { TouchScope.exit(); }
    }
}
