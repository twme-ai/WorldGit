package org.worldgit.platform;

import java.time.Duration;
import java.util.*;
import org.worldgit.core.model.ChunkPos;

/** active=true 時保護持續到同 operation 的 active=false；結束後再延續 duration。 */
public record PlayerProtection(Set<ChunkPos> chunks,Duration duration,Set<Damage> causes,
    UUID operation, boolean active) {
  public enum Damage { SUFFOCATION, FALL, DROWNING }
  public PlayerProtection { chunks=Set.copyOf(chunks); causes=Set.copyOf(causes); if(duration.isNegative()) throw new IllegalArgumentException("duration"); }
  public PlayerProtection(Set<ChunkPos> chunks, Duration duration, Set<Damage> causes) {
    this(chunks,duration,causes,null,false);
  }
  public static PlayerProtection defaults(Set<ChunkPos> chunks) {
    return new PlayerProtection(chunks,Duration.ofSeconds(10),EnumSet.allOf(Damage.class));
  }
  public static PlayerProtection operation(Set<ChunkPos> chunks, UUID operation, boolean active) {
    return new PlayerProtection(chunks,Duration.ofSeconds(10),EnumSet.allOf(Damage.class),operation,active);
  }
}
