package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 比較沿用每個請求的解碼預算（DecodeBudget）：預算太小時回 413，而不是無上限地解碼。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class BranchBudgetTest extends AbstractBranchTest {
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    prepareDirs();
    r.add("server.address", () -> "127.0.0.1");
    r.add("server.port", () -> "18096");
    r.add("worldgit.hub.data-dir", () -> data.toString());
    r.add("worldgit.hub.bootstrap.admin-token", () -> TOKEN);
    r.add("worldgit.hub.bootstrap.admin-password", () -> "test-password-1");
    r.add("worldgit.hub.limits.decoded-bytes", () -> "64"); // 一個 section 解壓後就會超過
  }

  @Test
  void compareExceedingDecodeBudgetIsRejectedWith413() throws Exception {
    String base = "/api/v1/worlds/admin/" + WORLD;
    // 不需要解碼世界資料的端點照常可用
    assertEquals(200, get(base + "/branches", TOKEN).statusCode());
    var r = get(base + "/compare?a=main&b=feature", TOKEN);
    assertEquals(413, r.statusCode(), new String(r.body()));
    assertTrue(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8).contains("預算"));
  }
}
