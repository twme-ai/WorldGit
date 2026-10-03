package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 合併預覽受 DecodeBudget 約束：預算耗盡回 413，且不留下快取。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class MergePreviewBudgetTest extends AbstractBranchTest {
  @org.springframework.beans.factory.annotation.Autowired org.worldgit.hub.history.MergePreviewService previews;
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    prepareDirs();
    r.add("server.address", () -> "127.0.0.1");
    r.add("server.port", () -> "18098");
    r.add("worldgit.hub.auth.attempts", () -> "100000");
    r.add("worldgit.hub.limits.work", () -> "3000");
    r.add("worldgit.hub.data-dir", () -> data.toString());
    r.add("worldgit.hub.bootstrap.admin-token", () -> TOKEN);
    r.add("worldgit.hub.bootstrap.admin-password", () -> "test-password-1");
    HubTestDatabase.configure(r);
  }

  @Test void exhaustedBudgetIs413AndNothingIsCached() throws Exception {
    var r = get("/api/v1/worlds/admin/" + WORLD + "/merge-preview?ours=main&theirs=feature", TOKEN);
    assertEquals(413, r.statusCode(), new String(r.body()));
    assertEquals(0, previews.cacheEntries());
  }
}
