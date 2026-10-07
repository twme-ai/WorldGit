package org.worldgit.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.worldgit.fabric.TouchScope;
import org.worldgit.fabric.WorldGitMod;

/** 繁殖會清除 loveCause；在呼叫前固定玩家來源，finally 保證例外時也收回 scope。 */
@Mixin(Animal.class)
abstract class TouchScopeBreedingMixin {
    @WrapMethod(method = "spawnChildFromBreeding")
    private void worldgit$breed(ServerLevel level, Animal partner, Operation<Void> original) {
        var self = (Animal) (Object) this;
        var runtime = WorldGitMod.runtime(level.getServer());
        boolean playerCaused = self.getLoveCause() != null || partner.getLoveCause() != null
            || runtime != null && (runtime.touched().known(self.getUUID()) || runtime.touched().known(partner.getUUID()));
        TouchScope.enter(playerCaused ? TouchScope.Kind.BREEDING : TouchScope.Kind.NONE);
        try {
            original.call(level, partner);
            if (playerCaused) { WorldGitMod.touch(self); WorldGitMod.touch(partner); }
        } finally { TouchScope.exit(); }
    }
}
