package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.version.*;
import org.worldgit.i18n.MessageCatalog;

class VersionContractsTest {
  @AfterEach void restoreLocale() { Messages.configure(MessageCatalog.bundled(),"en_us"); }
  @Test void selectorUsesExactCatalogAndOnlyInstalledAdapters() {
    Messages.configure(MessageCatalog.bundled(),"en_us");
    assertEquals(Set.of("1.20.1","1.20.4","1.20.6","1.21.1","1.21.4","1.21.8","1.21.11","26.2"),NmsBridges.supported());
    for(String version:List.of("1.20.10","1.20.1-R0.1","26.1.2","26.2.1","26.3")) {
      var error=assertThrows(UnsupportedOperationException.class,()->NmsBridges.select(version,getClass().getClassLoader()));
      assertTrue(error.getMessage().contains(version));assertTrue(error.getMessage().contains("Available adapters"));
    }
    Messages.configure(MessageCatalog.bundled(),"zh_tw");
    assertTrue(assertThrows(UnsupportedOperationException.class,()->NmsBridges.select("1.20.2",getClass().getClassLoader())).getMessage().contains("尚未支援"));
  }
  static ConversionReport report(int from,int to,String rules,long count) {
    return new ConversionReport(from,to,rules,List.of(new ConversionReport.Loss("block","minecraft:short_grass","minecraft:grass",count,List.of("chunk 0,0"))));
  }
  @Test void confirmationBindsFullSourceTargetRulesLossAndWorld() throws Exception {
    var original=Map.of("minecraft:overworld/commit-a",report(3700,3465,"rules-a",1));
    var fingerprint=VersionConfirmations.fingerprint(original,"world-a");
    assertEquals(fingerprint,VersionConfirmations.fingerprint(new TreeMap<>(original),"world-a"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(original,"world-b"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(Map.of("minecraft:overworld/commit-b",report(3700,3465,"rules-a",1)),"world-a"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(Map.of("minecraft:overworld/commit-a",report(3839,3465,"rules-a",1)),"world-a"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(Map.of("minecraft:overworld/commit-a",report(3700,3839,"rules-a",1)),"world-a"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(Map.of("minecraft:overworld/commit-a",report(3700,3465,"rules-b",1)),"world-a"));
    assertNotEquals(fingerprint,VersionConfirmations.fingerprint(Map.of("minecraft:overworld/commit-a",report(3700,3465,"rules-a",2)),"world-a"));
  }
}
