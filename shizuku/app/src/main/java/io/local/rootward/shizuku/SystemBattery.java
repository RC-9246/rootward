package io.local.rootward.shizuku;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/** Picks one present internal system battery, or reports that the target is ambiguous. */
final class SystemBattery {
  static final class Ambiguous extends IOException {}

  interface InodeReader {
    String read(Path path) throws IOException;
  }

  static volatile InodeReader legacyInodeReader;

  // A recreated same-name driver may receive a different sysfs inode within a boot.
  static String identity(Path real) throws IOException {
    InodeReader legacy = legacyInodeReader;
    if (legacy != null) return real + "@" + legacy.read(real);
    Object key = Files.readAttributes(real, BasicFileAttributes.class).fileKey();
    // The Nougat NIO backport supplies a numeric path hash even if the inode changes.
    return key == null || key instanceof Number ? "" : real + "@" + key;
  }

  static Path select(Path supplies) throws IOException {
    Map<Path, Path> candidates = new LinkedHashMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(supplies)) {
      for (Path p : entries) {
        String name = p.getFileName().toString();
        boolean conventional = name.equalsIgnoreCase("battery") || name.matches("BAT[0-9]+");
        try {
          if (!Files.readAttributes(p, BasicFileAttributes.class).isDirectory()) continue;
          if (!"Battery".equals(text(p.resolve("type")))) continue;
          String scope = optional(p.resolve("scope"));
          if (!"System".equals(scope)
              && !(conventional && (scope == null || scope.equals("Unknown")))) continue;
          String present = optional(p.resolve("present"));
          if (present != null && !present.equals("1")) continue;
          candidates.put(p.toRealPath(), p);
        } catch (NoSuchFileException gone) {
          // A supply can disappear during enumeration.
        } catch (AccessDeniedException denied) {
          if (conventional) throw denied;
        }
      }
    }
    if (candidates.size() > 1) throw new Ambiguous();
    return candidates.isEmpty() ? null : candidates.values().iterator().next();
  }

  private static String optional(Path p) throws IOException {
    try {
      return text(p);
    } catch (NoSuchFileException absent) {
      return null;
    }
  }

  static String text(Path p) throws IOException {
    return text(p, 96);
  }

  static String text(Path p, int limit) throws IOException {
    byte[] buf = new byte[limit];
    int count = 0;
    try (InputStream in = Files.newInputStream(p)) {
      while (count < buf.length) {
        int n = in.read(buf, count, buf.length - count);
        if (n < 0) return new String(buf, 0, count, StandardCharsets.US_ASCII).trim();
        count += n;
      }
    }
    throw new IOException("oversized attribute");
  }
}
