package org.worldgit.paper;

import java.io.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.worldgit.core.anvil.Nbt;

/** 1.20.6／1.21.1 已逐版確認一致的 codec；保留完整 components 與 custom_data。 */
public final class ComponentsNbtCodec {
  private ComponentsNbtCodec() {}
  public static CompoundTag toTag(Nbt.Compound tag) throws IOException {
    return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Nbt.write(tag))),NbtAccounter.unlimitedHeap());
  }
  public static Nbt.Compound fromTag(CompoundTag tag) throws IOException {
    var bytes=new ByteArrayOutputStream(4096);
    NbtIo.write(tag,new DataOutputStream(bytes));
    return Nbt.read(bytes.toByteArray());
  }
  public static BlockEntity loadBlockEntity(ServerLevel level,BlockPos pos,BlockState state,Nbt.Compound tag) throws IOException {
    return BlockEntity.loadStatic(pos,state,toTag(tag),level.registryAccess());
  }
}
