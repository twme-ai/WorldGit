package org.worldgit.fabric.logic;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import org.worldgit.protocol.MergeProtocol;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/** 客戶端回覆 hello：核對版本後回傳相同 nonce、客戶端能力與實際使用的色票。 */
public final class ClientHandshake {
  private ClientHandshake() {}

  public static Optional<Protocol.Hello> reply(Protocol.Hello server, ClientConfig config) {
    if (server.version() != Protocol.VERSION) return Optional.empty();
    return Optional.of(
        new Protocol.Hello(
            Protocol.VERSION, server.nonce(), capabilities(), config.resolve(server.palette())));
  }

  public static List<String> capabilities() {
    var result = new ArrayList<>(Protocol.CAPABILITIES);
    result.add(MergeProtocol.CAPABILITY);
    return List.copyOf(result);
  }

  public static DiffPalette palette(ClientConfig config, DiffPalette serverPalette) {
    return config.resolve(serverPalette);
  }
}
