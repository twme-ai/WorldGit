package org.worldgit.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.worldgit.fabric.WorldGitMod;

/** 主控台／RCON／玩家共用入口；與 Paper 的操作期間指令屏障一致。 */
@Mixin(Commands.class)
abstract class CommandEditMixin {
    @Inject(method="performCommand",at=@At("HEAD"),cancellable=true)
    private void worldgit$guard(ParseResults<CommandSourceStack> parse,String command,CallbackInfo ci) {
        var runtime=WorldGitMod.runtime(parse.getContext().getSource().getServer());
        if(runtime!=null && runtime.editsLocked() && !runtime.internalMutation()) {
            var name=Commands.trimOptionalPrefix(command).split(" ",2)[0].toLowerCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("wg","worldgit","wgit","git","stop","list","tps","mspt","say","msg","tell").contains(name)) ci.cancel();
        }
    }
}
