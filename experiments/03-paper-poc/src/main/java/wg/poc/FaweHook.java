package wg.poc;

import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.extent.Extent;

/** 只在 FAWE 存在時才會被載入：用 IBatchProcessor 觀察 chunk 層級的批次寫入。 */
final class FaweHook {
    private FaweHook() {}

    static Extent wrap(Extent ex, We.Sess s) {
        // 先在目前 extent 鏈上加一個 post-processor；FAWE 會在把 IChunkSet 送進 chunk 之前呼叫它。
        return ex.addProcessor(new Proc(s));
    }

    static Extent log(Extent ex, We.Sess s) { return new FaweLog(ex, s); }

    static final class FaweLog extends We.Log {
        FaweLog(Extent e, We.Sess s) { super(e, s); }
        // commit() 在 FAWE 的 AbstractDelegateExtent 是一般方法，但在純 WorldEdit 7.4 是 final（覆寫會 IncompatibleClassChangeError），所以只放在這個 FAWE 專用子類
        @Override public com.sk89q.worldedit.function.operation.Operation commit() { s.commits++; s.thread(); return super.commit(); }
        @Override public boolean tile(int x, int y, int z, com.fastasyncworldedit.core.nbt.FaweCompoundTag t) throws com.sk89q.worldedit.WorldEditException {
            s.tile.incrementAndGet(); return super.tile(x, y, z, t);
        }
    }

    static final class Proc implements IBatchProcessor {
        private final We.Sess s;
        Proc(We.Sess s) { this.s = s; }
        @Override public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
            int n = 0;
            int min = set.getMinSectionPosition(), max = set.getMaxSectionPosition();
            for (int l = min; l <= max; l++) {
                if (!set.hasSection(l)) continue;
                char[] arr = set.loadIfPresent(l);
                if (arr == null) continue;
                for (char c : arr) if (c != 0) n++;
            }
            s.procChunks.merge(chunk.getX() + "," + chunk.getZ(), n, Integer::sum);
            s.thread();
            return set;
        }
        @Override public Extent construct(Extent child) { return child; }
    }
}
