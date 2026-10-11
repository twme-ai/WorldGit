package org.worldgit.core.version;

import java.io.*;
import org.worldgit.core.anvil.Nbt;

/** 平台可直接提供伺服器 DFU；離線實作在獨立 JVM 執行使用者的原版 jar。 */
public interface NbtUpgrader extends AutoCloseable {
  Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to) throws IOException;

  @Override
  default void close() throws IOException {}
}
