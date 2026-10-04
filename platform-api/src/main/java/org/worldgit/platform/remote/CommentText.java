package org.worldgit.platform.remote;

/** Hub 資料當純文字。移除控制／雙向格式字元及 legacy 色碼，不解析 HTML/MiniMessage。 */
public final class CommentText {
  private CommentText() {}
  public static String plain(String input,int max) {
    if(input==null) return "";
    String safe=input.replaceAll("§[0-9A-FK-ORXa-fk-orx]","");
    var out=new StringBuilder(); int used=0;
    for(int cp:safe.codePoints().toArray()) {
      if(Character.isISOControl(cp) || Character.getType(cp)==Character.FORMAT) {if(cp=='\n' || cp=='\t')cp=' ';else continue;}
      if(used++>=max) { out.append('…'); break; } out.appendCodePoint(cp);
    }
    return out.toString();
  }
  public static String display(HubClient.Comment comment) { return plain(comment.username(),32)+": "+plain(comment.body(),240); }
}
