package org.worldgit.protocol;

import java.io.IOException;
import java.util.*;

/** 僅在主執行緒使用；完整批次才發布，clear 會取消尚未完成的批次。 */
public final class Assembler {
    private long floor = -1, active = -1, started;
    private Protocol.Part header;
    private final Map<Integer, Protocol.Part> pending = new HashMap<>();
    private int received;
    public void clear(long id) { floor = Math.max(floor, id); reset(); }
    public void reset() { pending.clear(); header = null; received = 0; active = -1; }
    public List<Protocol.Entry> accept(Protocol.Part part, long nowMillis) throws IOException {
        if (part.preview() <= floor) return null;
        if (header != null && nowMillis - started > 30_000) reset();
        if (header == null || part.preview() > active) { reset(); header = part; active = part.preview(); started = nowMillis; }
        if (part.preview() < active) return null;
        if (part.parts() != header.parts() || part.totalEntries() != header.totalEntries() || !part.dimension().equals(header.dimension())) throw new IOException("inconsistent batch");
        if (pending.containsKey(part.sequence())) throw new IOException("duplicate part");
        if (received + part.entries().size() > part.totalEntries()) throw new IOException("batch count overflow");
        pending.put(part.sequence(), part); received += part.entries().size();
        if (pending.size() != part.parts()) return null;
        if (received != part.totalEntries()) throw new IOException("incomplete count");
        var result = new ArrayList<Protocol.Entry>(received); var positions = new HashSet<String>();
        for (int i = 0; i < part.parts(); i++) for (var e : pending.get(i).entries()) {
            if (!positions.add(e.x() + "," + e.y() + "," + e.z())) throw new IOException("duplicate position"); result.add(e);
        }
        floor = active; reset(); return List.copyOf(result);
    }
}
