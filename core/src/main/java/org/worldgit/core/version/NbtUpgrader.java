package org.worldgit.core.version;

import java.io.*;
import org.worldgit.core.anvil.Nbt;

/** 平台可直接提供伺服器 DFU；離線實作在獨立 JVM 執行使用者的原版 jar。 */
public interface NbtUpgrader extends AutoCloseable {
  Nbt.Compound upgrade(String type, Nbt.Compound input, int from, int to) throws IOException;

  /** 平台在完成 schema 轉換後補原版載入預設；不得覆蓋使用者內容。 */
  default java.util.SortedMap<String,byte[]> projectMetadata(java.util.SortedMap<String,byte[]> metadata,
      int from,int to) throws IOException { return metadata; }
  /** 快取必須區分有不同平台預設的投影。 */
  default String projectionFingerprint() { return "vanilla"; }

  @Override
  default void close() throws IOException {}
}
