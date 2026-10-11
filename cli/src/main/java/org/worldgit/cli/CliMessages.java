package org.worldgit.cli;

import org.worldgit.i18n.MessageCatalog;

/** CLI 使用共用 MiniMessage 純文字樣板；ANSI 色彩由終端 renderer 決定。 */
final class CliMessages {
  private static final MessageCatalog CATALOG=MessageCatalog.bundled();
  static String text(String key,Object... pairs) {
    String locale=System.getenv().getOrDefault("WGIT_LOCALE","zh_tw");
    String template=CATALOG.raw(locale,"cli.phase5."+key);
    for(int i=0;i<pairs.length;i+=2) template=template.replace("<"+pairs[i]+">",String.valueOf(pairs[i+1]));
    return template;
  }
  static String version(String key,Object... pairs) {
    String template=CATALOG.raw(System.getenv().getOrDefault("WGIT_LOCALE","zh_tw"),"multiversion."+key);
    template=template.replaceAll("</?(?:green|yellow|gray)>","");
    for(int i=0;i<pairs.length;i+=2)template=template.replace("<"+pairs[i]+">",String.valueOf(pairs[i+1]));
    return template;
  }
}
