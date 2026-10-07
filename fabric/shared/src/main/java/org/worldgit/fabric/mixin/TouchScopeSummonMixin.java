package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.worldgit.fabric.TouchScope;

/** /summon（含 console／execute），語法失敗不能讓 scope 流入後續自然生成。 */
@Mixin(net.minecraft.server.commands.SummonCommand.class)
abstract class TouchScopeSummonMixin {
    @WrapMethod(method = "spawnEntity")
    private static int worldgit$summon(CommandSourceStack source, Holder.Reference<EntityType<?>> type, Vec3 pos,
            CompoundTag tag, boolean initialize, Operation<Integer> original) {
        TouchScope.enter(TouchScope.Kind.COMMAND);
        try { return original.call(source, type, pos, tag, initialize); }
        finally { TouchScope.exit(); }
    }
}
