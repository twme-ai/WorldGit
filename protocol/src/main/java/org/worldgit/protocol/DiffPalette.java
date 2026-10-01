package org.worldgit.protocol;

import java.util.*;
import org.worldgit.core.diff.ChangeKind;

/** 所有 UI 使用此色票；同時使用 symbol/style，不能只靠顏色。 */
public record DiffPalette(Map<ChangeKind, Integer> colors) {
  public static final DiffPalette DEFAULT =
      new DiffPalette(
          Map.of(
              ChangeKind.ADDED,
              0x3FB950,
              ChangeKind.REMOVED,
              0xF85149,
              ChangeKind.MODIFIED,
              0xD29922,
              ChangeKind.CONFLICT,
              0xA371F7));
  public static final DiffPalette COLORBLIND =
      new DiffPalette(
          Map.of(
              ChangeKind.ADDED,
              0x0072B2,
              ChangeKind.REMOVED,
              0xE69F00,
              ChangeKind.MODIFIED,
              0xF0E442,
              ChangeKind.CONFLICT,
              0xCC79A7));

  public DiffPalette {
    colors = Map.copyOf(colors);
    if (colors.size() != 4
        || !colors.keySet().containsAll(EnumSet.allOf(ChangeKind.class))
        || colors.values().stream().anyMatch(n -> n < 0 || n > 0xFFFFFF))
      throw new IllegalArgumentException("色票必須包含四種 24-bit RGB");
  }

  public int rgb(ChangeKind kind) {
    return colors.get(kind);
  }

  public static char symbol(ChangeKind kind) {
    return switch (kind) {
      case ADDED -> '+';
      case REMOVED -> '-';
      case MODIFIED -> '~';
      case CONFLICT -> '!';
    };
  }

  public static String style(ChangeKind kind) {
    return switch (kind) {
      case ADDED -> "solid-outline";
      case REMOVED -> "ghost-model";
      case MODIFIED -> "corner-outline";
      case CONFLICT -> "pulsing-outline";
    };
  }
}
