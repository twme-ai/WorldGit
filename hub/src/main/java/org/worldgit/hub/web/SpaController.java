package org.worldgit.hub.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 前端是單頁應用：非 API、非靜態檔的路徑都回 index.html（由前端路由處理）。 */
@Controller
public class SpaController {
  @GetMapping({"/", "/new", "/login", "/settings", "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}", "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}/commits",
      "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}/branches",
      "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}/compare/{*spec}",
      "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}/merge-preview/{*spec}",
      "/{owner:[a-z0-9_-]+}/{world:[a-z0-9_-]+}/commit/{dim}/{rev}", "/{owner:[a-z0-9_-]+}"})
  public String index() {
    return "forward:/index.html";
  }
}
