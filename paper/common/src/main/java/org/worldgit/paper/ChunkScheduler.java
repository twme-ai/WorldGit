package org.worldgit.paper;

import org.bukkit.World;

/** 快照複製所需的兩種 owner 排程；獨立介面讓背壓與取消行為可在沒有伺服器的情況下驗證。 */
interface ChunkScheduler {
  void region(World world, int chunkX, int chunkZ, Runnable task);
  void regionDelayed(World world, int chunkX, int chunkZ, long ticks, Runnable task);
}
