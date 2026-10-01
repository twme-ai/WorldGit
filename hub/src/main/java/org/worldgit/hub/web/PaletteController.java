package org.worldgit.hub.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.protocol.DiffPalette;

/** 四端共用的 diff 色票（決定 #15）直接取自 protocol 模組，網頁不另外寫死顏色。 */
@RestController
@RequestMapping("/api/v1")
public class PaletteController {
  private static Map<String, String> hex(DiffPalette p) {
    var m = new LinkedHashMap<String, String>();
    for (ChangeKind k : ChangeKind.values()) m.put(k.name().toLowerCase(Locale.ROOT), String.format("#%06X", p.rgb(k)));
    return m;
  }

  @GetMapping("/diff-palettes")
  Map<String, Object> palettes() {
    var symbols = new LinkedHashMap<String, String>();
    var styles = new LinkedHashMap<String, String>();
    for (ChangeKind k : ChangeKind.values()) {
      symbols.put(k.name().toLowerCase(Locale.ROOT), String.valueOf(DiffPalette.symbol(k)));
      styles.put(k.name().toLowerCase(Locale.ROOT), DiffPalette.style(k));
    }
    return Map.of("default", hex(DiffPalette.DEFAULT), "colorblind", hex(DiffPalette.COLORBLIND), "symbols", symbols, "styles", styles);
  }
}
