package org.worldgit.fabric;

import java.util.ArrayDeque;

/**
 * 「玩家造成的動作」範圍（伺服器執行緒）：mixin 在玩家使用物品、/summon、玩家繁殖與擲蛋時進入，
 * 這段期間內新加入世界的實體才算 player-touched；自然生成、掉落物、經驗球不在範圍內。
 * 每個 tick 開始清空，避免例外中斷的範圍外洩到後續自然生成。
 */
public final class TouchScope {
    private TouchScope() {}

    public enum Kind { NONE, USE, COMMAND, BREEDING, THROWN, AXIOM }

    private static final ThreadLocal<ArrayDeque<Kind>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    public static void enter(Kind kind) { STACK.get().push(kind); }

    public static void exit() {
        var stack = STACK.get();
        if (!stack.isEmpty()) stack.pop();
    }

    /** 目前範圍；沒有或 NONE 時為 null（實體不算玩家觸及）。 */
    public static Kind current() {
        var kind = STACK.get().peek();
        return kind == Kind.NONE ? null : kind;
    }

    public static void reset() { STACK.get().clear(); }
}
