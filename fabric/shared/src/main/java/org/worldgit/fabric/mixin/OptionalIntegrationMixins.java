package org.worldgit.fabric.mixin;

import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Axiom 未安裝時不嘗試尋找它的類別，也不掛接其專用 Minecraft lookup。 */
public final class OptionalIntegrationMixins implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) {
        return !mixin.substring(mixin.lastIndexOf('.')+1).startsWith("Axiom") || FabricLoader.getInstance().isModLoaded("axiom");
    }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
