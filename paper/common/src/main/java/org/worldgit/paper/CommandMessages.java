package org.worldgit.paper;
import com.mojang.brigadier.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
final class CommandMessages {
  static java.util.function.Function<Component,Message> serializer=value->()->PlainTextComponentSerializer.plainText().serialize(value);
  static Message serialize(Component value) { return serializer.apply(value); }
}
