package org.worldgit.paper;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** hide／換維度／離線後，晚到的 REST 結果不能重新顯示。 */
final class DisplayRequests {
  private final AtomicLong sequence=new AtomicLong();
  private final ConcurrentHashMap<UUID,Long> active=new ConcurrentHashMap<>();
  long reserve(UUID player) {long id=sequence.incrementAndGet();active.put(player,id);return id;}
  boolean current(UUID player,long id) {return java.util.Objects.equals(active.get(player),id);}
  void cancel(UUID player) {active.remove(player);}
  void clear() {active.clear();}
}
