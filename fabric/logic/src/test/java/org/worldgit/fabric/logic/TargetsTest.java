package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.worldgit.core.model.DimensionId;

class TargetsTest {
  @Test
  void defaultsToTheCurrentDimension() {
    var t = Targets.parse("--full --blocks", false);
    assertEquals(Targets.Kind.CURRENT, t.kind());
    assertNull(t.dimension());
    assertEquals("--full --blocks", t.rest());
  }

  @Test
  void explicitDimensionAndAllAreExtractedAnywhere() {
    var explicit = Targets.parse("--full --dimension minecraft:the_nether --blocks", false);
    assertEquals(Targets.Kind.EXPLICIT, explicit.kind());
    assertEquals(new DimensionId("minecraft:the_nether"), explicit.dimension());
    assertEquals("--full --blocks", explicit.rest());
    assertEquals(new DimensionId("minecraft:the_end"), Targets.parse("--dimension=minecraft:the_end", false).dimension());
    var all = Targets.parse("1 --all", false);
    assertEquals(Targets.Kind.ALL, all.kind());
    assertEquals("1", all.rest());
  }

  @Test
  void greedyTextAfterTheLeadingOptionsKeepsItsLiteralOptionsAndSpacing() {
    var t = Targets.parse("--all -m fix  --all  and   spacing", true);
    assertEquals(Targets.Kind.ALL, t.kind());
    assertEquals("-m fix  --all  and   spacing", t.rest());
    var none = Targets.parse("-m message --dimension minecraft:the_end", true);
    assertEquals(Targets.Kind.CURRENT, none.kind());
    assertEquals("-m message --dimension minecraft:the_end", none.rest());
    assertEquals("add entity minecraft:cow", Targets.parse("--dimension minecraft:the_nether add entity minecraft:cow", true).rest());
  }

  @Test
  void targetWordsInsideMessagesTitlesAndCommentsStayLiteral() {
    var stash = Targets.parseCommand("stash", "push --dimension minecraft:the_end message  --all", false);
    assertEquals(new DimensionId("minecraft:the_end"), stash.dimension());
    assertEquals("push message  --all", stash.rest());
    var pr = Targets.parseCommand("pr", "--all create --source feature title --dimension minecraft:the_end", false);
    assertEquals(Targets.Kind.ALL, pr.kind());
    assertEquals("create --source feature title --dimension minecraft:the_end", pr.rest());
    var comment = Targets.parseCommand("comment", "12 --dimension minecraft:the_nether --here body --all", false);
    assertEquals(new DimensionId("minecraft:the_nether"), comment.dimension());
    assertEquals("12 --here body --all", comment.rest());
  }

  @Test
  void conflictsAndMissingValuesAreTranslatableErrors() {
    var exclusive = assertThrows(CommandArgs.Invalid.class, () -> Targets.parse("--all --dimension minecraft:the_end", false));
    assertEquals(MessageKeys.ERROR_TARGET_EXCLUSIVE, exclusive.msg().key());
    assertEquals(MessageKeys.ERROR_OPTION_NEEDS_VALUE, assertThrows(CommandArgs.Invalid.class, () -> Targets.parse("--dimension", false)).msg().key());
    assertEquals(MessageKeys.ERROR_INVALID_DIMENSION, assertThrows(CommandArgs.Invalid.class, () -> Targets.parse("--dimension NOT_AN_ID", false)).msg().key());
    assertThrows(CommandArgs.Invalid.class, () -> Targets.parse("--all --all", false));
    assertThrows(CommandArgs.Invalid.class, () -> Targets.parse("--dimension minecraft:a --dimension minecraft:b", false));
  }
}
