package org.worldgit.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.worldgit.protocol.Protocol;
import org.worldgit.protocol.MergeProtocol;

/**
 * worldgit:* plugin channel 的 payload。內容是 protocol 模組編碼好的 bytes（每包 ≤ 28,000 bytes）；
 * codec 本身也檢查上限，不依賴 vanilla 的 1 MiB／32 KiB fallback。與 Paper 插件使用同一份 wire format。
 */
public final class Net {
    private Net() {}

    public record Payload(CustomPacketPayload.Type<Payload> type, byte[] bytes) implements CustomPacketPayload {}

    public record Channel(CustomPacketPayload.Type<Payload> type, StreamCodec<RegistryFriendlyByteBuf, Payload> codec) {
        public Payload of(byte[] bytes) {
            return new Payload(type, bytes);
        }
    }

    private static Channel channel(String name) {
        var type = new CustomPacketPayload.Type<Payload>(Identifier.parse(name));
        var codec = new StreamCodec<RegistryFriendlyByteBuf, Payload>() {
            @Override
            public Payload decode(RegistryFriendlyByteBuf buf) {
                int n = buf.readableBytes();
                if (n > Protocol.MAX_PAYLOAD) throw new IllegalArgumentException("oversize payload: " + n);
                byte[] bytes = new byte[n];
                buf.readBytes(bytes);
                return new Payload(type, bytes);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, Payload payload) {
                if (payload.bytes().length > Protocol.MAX_PAYLOAD)
                    throw new IllegalArgumentException("oversize payload: " + payload.bytes().length);
                buf.writeBytes(payload.bytes());
            }
        };
        return new Channel(type, codec);
    }

    public static final Channel HELLO = channel(Protocol.HELLO);
    public static final Channel DIFF = channel(Protocol.DIFF);
    public static final Channel STATUS = channel(Protocol.STATUS);
    public static final Channel CLEAR = channel(Protocol.CLEAR);

    public static final Channel COMMENTS = channel(org.worldgit.protocol.CommentsProtocol.CHANNEL);

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

    private static boolean registered;

    /** 伺服端與客戶端都要呼叫一次（單人世界兩邊在同一個 JVM，所以是冪等的）。 */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Platform.s2c().register(HELLO.type(), HELLO.codec());
        Platform.c2s().register(HELLO.type(), HELLO.codec());
        Platform.s2c().register(DIFF.type(), DIFF.codec());
        Platform.s2c().register(STATUS.type(), STATUS.codec());
        Platform.s2c().register(CLEAR.type(), CLEAR.codec());
        Platform.s2c().register(COMMENTS.type(), COMMENTS.codec());
        Platform.s2c().register(CONFLICTS.type(), CONFLICTS.codec());
        Platform.s2c().register(CONFLICT_PREVIEW.type(), CONFLICT_PREVIEW.codec());
    }
}
