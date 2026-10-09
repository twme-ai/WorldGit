package org.worldgit.fabric.mixin;
import java.util.function.LongPredicate;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LevelTicks.class)
public interface LevelTicksAccess {
  @Accessor("tickCheck") LongPredicate worldgit$check();
  @Mutable @Accessor("tickCheck") void worldgit$check(LongPredicate check);
}
