package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.worldgit.core.version.MinecraftVersion;
import org.worldgit.i18n.MessageCatalog;

class NmsBridgesTest {
  @AfterEach void restoreLocale() { Messages.configure(MessageCatalog.bundled(), "en_us"); }
  @Test void exactCatalogEntriesLoadOnlyTheirOwnAdapter() throws Exception {
    Messages.configure(MessageCatalog.bundled(), "en_us");
    for (var version : NmsBridges.supported()) {
      assertEquals(version, MinecraftVersion.byId(version).id());
      var requested = new ArrayList<String>();
      var loader = new ClassLoader(null) {
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
          requested.add(name);
          throw new ClassNotFoundException(name);
        }
      };
      assertThrows(IllegalStateException.class, () -> NmsBridges.select(version, loader));
      String suffix = version.replace('.', '_');
      assertEquals(List.of("org.worldgit.paper.v" + suffix + ".Bridge_" + suffix), requested);
    }
  }

  @Test void unavailableFoliaFailsBeforeLoadingAnyAdapter() {
    for(String locale:List.of("en_us","zh_tw")) {
      Messages.configure(MessageCatalog.bundled(),locale);
      var loader=new ClassLoader(null) {
        @Override public Class<?> loadClass(String name) {throw new AssertionError(name);}
      };
      for(String version:List.of("1.21.1","26.3")) {
        var error=assertThrows(UnsupportedOperationException.class,()->NmsBridges.select(version,loader,true));
        assertTrue(error.getMessage().contains("Folia "+version));
        assertTrue(error.getMessage().contains(locale.equals("zh_tw")?"官方沒有":"no official build"));
      }
    }
  }

  @Test void unsupportedCatalogAndSimilarVersionsNeverLoadAnAdapter() {
    for (String locale : List.of("en_us", "zh_tw")) {
      Messages.configure(MessageCatalog.bundled(), locale);
      var loader = new ClassLoader(null) {
        @Override public Class<?> loadClass(String name) { throw new AssertionError(name); }
      };
      for (String version : List.of("1.20", "1.20.2", "1.20.10", "1.21.11.1", "26.20", "1.21.4")) {
        var error = assertThrows(UnsupportedOperationException.class, () -> NmsBridges.select(version, loader));
        assertTrue(error.getMessage().contains(version));
        assertTrue(error.getMessage().contains("1.20.1"));
        assertTrue(error.getMessage().contains("26.2"));
      }
    }
  }
}
