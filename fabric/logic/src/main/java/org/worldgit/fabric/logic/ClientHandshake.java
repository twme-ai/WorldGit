package org.worldgit.fabric.logic;

import java.util.Optional;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

/** 客戶端回覆 hello：核對版本後回傳相同 nonce、客戶端能力與實際使用的色票。 */
public final class ClientHandshake {
  private ClientHandshake() {}

  public static Optional<Protocol.Hello> reply(Protocol.Hello server, ClientConfig config) {
    if (server.version() != Protocol.VERSION) return Optional.empty();
    return Optional.of(
        new Protocol.Hello(
            Protocol.VERSION, server.nonce(), Protocol.CAPABILITIES, config.resolve(server.palette())));
  }

  public static DiffPalette palette(ClientConfig config, DiffPalette serverPalette) {
    return config.resolve(serverPalette);
  }
}
