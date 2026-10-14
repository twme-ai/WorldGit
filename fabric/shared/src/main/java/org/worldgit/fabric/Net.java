package org.worldgit.fabric;

import org.worldgit.protocol.Protocol;
import org.worldgit.protocol.MergeProtocol;

/**
 * worldgit:* plugin channel 的 payload。內容是 protocol 模組編碼好的 bytes（每包 ≤ 28,000 bytes）；
 * codec 本身也檢查上限，不依賴 vanilla 的 1 MiB／32 KiB fallback。與 Paper 插件使用同一份 wire format。
 */
public final class Net {
    private Net() {}

    public record Channel(String id) {}
    private static Channel channel(String name) { return new Channel(name); }

    public static final Channel HELLO = channel(Protocol.HELLO);
    public static final Channel DIFF = channel(Protocol.DIFF);
    public static final Channel STATUS = channel(Protocol.STATUS);
    public static final Channel CLEAR = channel(Protocol.CLEAR);

    public static final Channel COMMENTS = channel(org.worldgit.protocol.CommentsProtocol.CHANNEL);

    /** Fabric 專屬 UI（進度 HUD、分支圖畫面、.wgignore 畫面）；雙向。 */
    public static final Channel UI = channel(org.worldgit.fabric.logic.UiProtocol.CHANNEL);

    public static final Channel CONFLICTS = channel(MergeProtocol.REGIONS);
    public static final Channel CONFLICT_PREVIEW = channel(MergeProtocol.PREVIEW);

    /** 依 protocol 封包第二個 byte（message kind）選 channel：0 hello、1 clear、2 diff、3 status。 */
    public static Channel forPacket(byte[] bytes) {
        if (bytes.length < 2) throw new IllegalArgumentException("packet too short");
        if (bytes[0] == MergeProtocol.VERSION) return switch (bytes[1]) {
            case 0 -> CONFLICTS;
            case 1 -> CONFLICT_PREVIEW;
            case 2 -> COMMENTS;
            default -> throw new IllegalArgumentException("unknown merge message kind");
        };
        return switch (bytes[1]) {
            case 0 -> HELLO;
            case 1 -> CLEAR;
            case 2 -> DIFF;
            case 3 -> STATUS;
            default -> throw new IllegalArgumentException("unknown message kind " + bytes[1]);
        };
    }

    public static void check(byte[] bytes) {
        if (bytes.length > Protocol.MAX_PAYLOAD) throw new IllegalArgumentException("oversize payload: " + bytes.length);
    }
}
