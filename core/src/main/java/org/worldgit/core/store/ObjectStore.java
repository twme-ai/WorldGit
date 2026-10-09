package org.worldgit.core.store;

import java.io.*;
import java.util.*;

public interface ObjectStore {
  enum Kind {
    BLOB,
    TREE
  }

  record Entry(String name, Kind kind, String id) {
    public Entry {
      if (name.isEmpty()
          || name.contains("/")
          || name.indexOf('\0') >= 0
          || name.equals(".")
          || name.equals("..")) throw new IllegalArgumentException("tree entry name");
      Objects.requireNonNull(kind);
      if (id.length()!=40) throw new IllegalArgumentException("object id");
      for (int i=0;i<40;i++) {
        char c=id.charAt(i);
        if (!(c>='0' && c<='9' || c>='a' && c<='f')) throw new IllegalArgumentException("object id");
      }
    }
  }

  String writeBlob(byte[] data) throws IOException;

  byte[] readBlob(String id) throws IOException;

  String writeTree(Collection<Entry> entries) throws IOException;

  SortedMap<String, Entry> readTree(String id) throws IOException;

  void flush() throws IOException;
}
