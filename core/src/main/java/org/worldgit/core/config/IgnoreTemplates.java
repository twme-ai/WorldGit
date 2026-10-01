package org.worldgit.core.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class IgnoreTemplates {
  private IgnoreTemplates() {}

  public static String load(String name) throws IOException {
    if (!Set.of("creative", "survival").contains(name))
      throw new IOException("template 必須是 creative|survival");
    try (var in =
        IgnoreTemplates.class.getResourceAsStream(
            "/org/worldgit/templates/" + name + ".wgignore")) {
      if (in == null) throw new IOException("範本不存在：" + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
