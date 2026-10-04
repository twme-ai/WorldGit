package org.worldgit.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.*;
import org.worldgit.core.remote.RemoteSpec;

/** 自訂型別只讀輸入；不做 repo、網路或世界存取。 */
final class CommandArguments {
  enum TokenKind { REVISION, BRANCH, REMOTE, URL }

  static CommandSyntaxException error(StringReader reader, Object source, String key, Object... args) {
    var message = source instanceof CommandSourceStack stack
        ? Messages.inLocale(stack.getSender(), () -> Messages.text(key, args)) : Messages.text(key, args);
    return new SimpleCommandExceptionType(MessageComponentSerializer.message().serialize(message)).createWithContext(reader);
  }

  static final class Token implements CustomArgumentType<String, String> {
    final TokenKind kind;
    Token(TokenKind kind) { this.kind = kind; }
    public ArgumentType<String> getNativeType() { return kind == TokenKind.URL ? StringArgumentType.greedyString() : StringArgumentType.string(); }
    public String parse(StringReader reader) throws CommandSyntaxException { return parse(reader, null); }
    public <S> String parse(StringReader reader, S source) throws CommandSyntaxException {
      int start = reader.getCursor();
      String value = token(reader);
      try {
        if (value.isBlank() || value.startsWith("--")) throw new IllegalArgumentException();
        switch (kind) {
          case REMOTE -> RemoteSpec.validateName(value);
          case URL -> RemoteSpec.validateUrl(value.replace("manifest+", "").replace("{dimension}", "minecraft.overworld"));
          case BRANCH -> {
            if (value.startsWith("-") || value.length() > 128 || value.contains("..") || value.contains("//")
                || value.endsWith("/") || value.endsWith(".") || value.endsWith(".lock")) throw new IllegalArgumentException();
          }
          default -> { }
        }
      } catch (java.io.IOException | IllegalArgumentException e) {
        reader.setCursor(start);
        throw error(reader, source, "paper.command.invalid-" + kind.name().toLowerCase(Locale.ROOT));
      }
      return value;
    }
  }

  /** 舊 #12 保留伺服器解析；兩個分支都傳送 integer(1)，讓主要數字寫法有原生型別提示。 */
  static final class PrefixedId implements CustomArgumentType<Integer, Integer> {
    public ArgumentType<Integer> getNativeType() { return IntegerArgumentType.integer(1); }
    public Integer parse(StringReader reader) throws CommandSyntaxException { return parse(reader, null); }
    public <S> Integer parse(StringReader reader, S source) throws CommandSyntaxException {
      int start = reader.getCursor();
      if (!reader.canRead() || reader.read() != '#') {
        reader.setCursor(start); throw error(reader, source, "paper.command.invalid-id");
      }
      return IntegerArgumentType.integer(1).parse(reader);
    }
  }

  record TextOptions(String text, Map<String, String> options) { }

  /** PR／留言的相容尾端。旗標使用同一 Token 型別；引號內的 --xxx 永遠是文字。 */
  static final class TextTail implements CustomArgumentType<TextOptions, String> {
    final boolean pr;
    final Set<String> used;
    TextTail(boolean pr, Set<String> used) { this.pr = pr; this.used = Set.copyOf(used); }
    public ArgumentType<String> getNativeType() { return StringArgumentType.greedyString(); }
    public TextOptions parse(StringReader reader) throws CommandSyntaxException { return parse(reader, null); }
    public <S> TextOptions parse(StringReader reader, S source) throws CommandSyntaxException {
      var options = new LinkedHashMap<String, String>();
      var text = new ArrayList<String>();
      while (reader.canRead()) {
        reader.skipWhitespace(); if (!reader.canRead()) break;
        int start = reader.getCursor();
        boolean quoted = StringReader.isQuotedStringStart(reader.peek());
        String word = token(reader);
        if (!quoted && pr && Set.of("--source", "--target").contains(word)) {
          if (options.containsKey(word) || used.contains(word)) {
            reader.setCursor(start); throw error(reader, source, "paper.command.duplicate-option", "option", word);
          }
          reader.skipWhitespace();
          options.put(word, new Token(TokenKind.BRANCH).parse(reader, source));
        } else if (!quoted && !pr && word.equals("--here")) {
          if (options.put(word, "true") != null || used.contains(word)) {
            reader.setCursor(start); throw error(reader, source, "paper.command.duplicate-option", "option", word);
          }
        } else if (!quoted && pr && word.startsWith("--")) {
          reader.setCursor(start); throw error(reader, source, "paper.error.unknown-option", "option", word);
        } else text.add(word);
      }
      if (text.isEmpty() || String.join(" ", text).isBlank()) throw error(reader, source, "paper.command.empty-text");
      return new TextOptions(String.join(" ", text), Map.copyOf(options));
    }
  }

  static String token(StringReader reader) throws CommandSyntaxException {
    if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) return reader.readString();
    int start = reader.getCursor();
    while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
    return reader.getString().substring(start, reader.getCursor());
  }

  /** Native string 的特殊字元須加引號；伺服器也保留未加引號的舊寫法。 */
  static String quote(String value) { return StringArgumentType.escapeIfRequired(value); }
  private CommandArguments() { }
}
