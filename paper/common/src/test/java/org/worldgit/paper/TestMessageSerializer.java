package org.worldgit.paper;

import com.mojang.brigadier.Message;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** API 的 serializer 是服務介面；純 JVM 測試保存完整 Component，正式 jar 不含本 provider。 */
public final class TestMessageSerializer implements MessageComponentSerializer {
  public record ComponentMessage(Component component) implements Message {
    public String getString() { return PlainTextComponentSerializer.plainText().serialize(component); }
  }
  public Message serialize(Component component) { return new ComponentMessage(component); }
  public Component deserialize(Message message) {
    return message instanceof ComponentMessage m ? m.component() : Component.text(message.getString());
  }
}
