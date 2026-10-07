package org.worldgit.fabric;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.core.graph.GraphText;
import org.worldgit.core.model.DimensionId;
import org.worldgit.fabric.logic.MessageKeys;
import org.worldgit.fabric.logic.Msg;
import org.worldgit.platform.ResultSummary;

/** /wg log --graph：core CommitGraph／GraphText 的聊天文字圖；標籤上色、hover 完整 commit、點選填入 diff 指令。 */
final class GraphChat {
  private GraphChat() {}

  private static final Key UNIFORM = Key.key("minecraft:uniform");

  static List<Component> lines(ServerRuntime rt, String locale, DimensionId dimension, CommitGraph graph, int page, int size) {
    var lines = new ArrayList<Component>();
    if (graph.nodes().isEmpty()) {
      lines.add(Texts.adventure(rt, locale, Msg.prefixed(MessageKeys.GRAPH_EMPTY, "dimension", dimension.value())));
      return lines;
    }
    lines.add(Texts.adventure(rt, locale, Msg.prefixed(MessageKeys.GRAPH_TITLE, "dimension", dimension.value(), "page", page)));
    for (var node : graph.nodes().stream().skip((long) (page - 1) * size).limit(size).toList()) {
      var row = Component.text(GraphText.node(node), NamedTextColor.GRAY).font(UNIFORM)
          .append(Component.text(ResultSummary.shortId(node.id()) + " ", NamedTextColor.YELLOW));
      for (var label : node.labels())
        row = row.append(Component.text("[" + label.name() + "] ", color(label.kind())));
      row = row.append(Component.text(node.message(), NamedTextColor.WHITE));
      var hover = Texts.adventure(rt, locale, Msg.of(MessageKeys.GRAPH_HOVER_SUFFIX, "id", node.id(), "author", node.author(),
          "time", node.time(), "message", node.message(), "parents", node.parents().isEmpty() ? "-" : String.join(", ", node.parents())));
      lines.add(row.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.suggestCommand("/wg diff " + node.id() + " --dimension " + dimension.value())));
      GraphText.transition(node).ifPresent(transition -> lines.add(Component.text(transition, NamedTextColor.GRAY).font(UNIFORM)));
    }
    if (graph.truncated() || graph.nodes().size() > page * size)
      lines.add(Texts.adventure(rt, locale, Msg.of(MessageKeys.GRAPH_MORE, "page", page + 1))
          .clickEvent(ClickEvent.suggestCommand("/wg log --graph --dimension " + dimension.value() + " --page " + (page + 1))));
    return lines;
  }

  static NamedTextColor color(String kind) {
    return switch (kind) {
      case "HEAD" -> NamedTextColor.RED;
      case "tag" -> NamedTextColor.GOLD;
      case "tracking" -> NamedTextColor.AQUA;
      default -> NamedTextColor.GREEN;
    };
  }
}
