import java.nio.file.*;
import java.util.*;
import org.worldgit.protocol.*;

/** 解碼 wgbot.js（WG_BOT_DUMP）收到的 worldgit:diff／status payload，輸出每個完整批次的格子／描邊（JSON 行）。 */
class DecodeDump {
  static String q(String s) { return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }
  public static void main(String[] args) throws Exception {
    var assembler = new BatchAssembler();
    var out = new StringBuilder("[");
    boolean first = true;
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      String[] p = line.trim().split(" ");
      if (p.length != 2) continue;
      byte[] b = HexFormat.of().parseHex(p[1]);
      var msg = Protocol.decode(b);
      var done = assembler.accept(msg, System.currentTimeMillis());
      if (done.isEmpty()) continue;
      var c = done.get();
      var sb = new StringBuilder();
      sb.append("{\"channel\":").append(q(p[0])).append(",\"preview\":").append(c.header().preview())
        .append(",\"dimension\":").append(q(c.header().dimension().toString())).append(",\"cells\":[");
      boolean f = true;
      for (var cell : c.cells()) {
        if (!f) sb.append(','); f = false;
        sb.append('[').append(cell.x()).append(',').append(cell.y()).append(',').append(cell.z()).append(',')
          .append(q(cell.kind().name())).append(',').append(q(cell.before())).append(',').append(q(cell.after())).append(']');
      }
      sb.append("],\"outlines\":[");
      f = true;
      for (var o : c.outlines()) {
        if (!f) sb.append(','); f = false;
        sb.append('[').append(o.x1()).append(',').append(o.y1()).append(',').append(o.z1()).append(',').append(o.x2()).append(',')
          .append(o.y2()).append(',').append(o.z2()).append(',').append(q(o.kind().name())).append(',').append(o.added()).append(',')
          .append(o.removed()).append(',').append(o.modified()).append(']');
      }
      sb.append("]}");
      if (!first) out.append(','); first = false;
      out.append(sb);
    }
    System.out.println(out.append(']'));
  }
}
