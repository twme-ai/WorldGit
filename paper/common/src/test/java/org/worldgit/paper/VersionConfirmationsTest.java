package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.version.ConversionReport;

class VersionConfirmationsTest {
  @Test void approvalBindsActorStateAndExpiry() {
    var approval = new VersionConfirmations.Approval(UUID.randomUUID(), "state", "player", 100);
    assertTrue(approval.permits("state", "player", 99));
    assertFalse(approval.permits("changed", "player", 99));
    assertFalse(approval.permits("state", "other-player", 99));
    assertFalse(approval.permits("state", "player", 100));
  }

  @Test void fingerprintBindsSourceTargetRulesAndWorldIndependentlyOfMapOrder() throws Exception {
    var first = new ConversionReport(3700, 3465, "rules-a", List.of());
    var second = new ConversionReport(4671, 3465, "rules-a", List.of());
    var reports = new LinkedHashMap<String, ConversionReport>();
    reports.put("overworld/first", first);
    reports.put("overworld/second", second);
    var reverse = new LinkedHashMap<String, ConversionReport>();
    reverse.put("overworld/second", second);
    reverse.put("overworld/first", first);
    var fingerprint = VersionConfirmations.fingerprint(reports, "world-and-head");
    assertEquals(fingerprint, VersionConfirmations.fingerprint(reverse, "world-and-head"));
    assertNotEquals(fingerprint, VersionConfirmations.fingerprint(reports, "changed-world-or-head"));
    reports.put("overworld/first", new ConversionReport(3700, 3465, "rules-b", List.of()));
    assertNotEquals(fingerprint, VersionConfirmations.fingerprint(reports, "world-and-head"));
    reports.put("overworld/first", new ConversionReport(3700, 3700, "rules-a", List.of()));
    assertNotEquals(fingerprint, VersionConfirmations.fingerprint(reports, "world-and-head"));
  }
}
