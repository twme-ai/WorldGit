package org.worldgit.fabric.logic;

import java.util.UUID;
import org.worldgit.core.model.CommitMetadata.Identity;

/** git 身分：玩家用名稱與 uuid 衍生的 email（不含真實信箱），伺服器／控制台用設定的名稱。 */
public final class Identities {
  private Identities() {}

  public static Identity player(String name, UUID id, String emailDomain) {
    return new Identity(clean(name), id + "@" + emailDomain);
  }

  public static Identity server(ServerConfig config) {
    return new Identity(config.identity().serverName(), config.identity().serverEmail());
  }

  private static String clean(String name) {
    String s = name == null ? "" : name.replaceAll("[\\r\\n<>]", "").trim();
    return s.isEmpty() ? "player" : s;
  }
}
