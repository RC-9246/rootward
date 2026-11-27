package io.local.rootward.shizuku;

import static io.local.rootward.shizuku.NativeLimits.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

public final class NativeLimitsTest {
  static final Writer NEVER =
      (p, n) -> {
        throw new AssertionError("unexpected write " + p);
      };
  static final String START_FILE = "charge_control_start_threshold",
      END_FILE = "charge_control_end_threshold";

  static void put(Path p, String value) throws IOException {
    Files.write(p, value.getBytes(StandardCharsets.US_ASCII));
  }

  static Path device(Path root, String name, String type, String scope) throws IOException {
    Path p = Files.createDirectory(root.resolve(name));
    put(p.resolve("type"), type);
    if (scope != null) put(p.resolve("scope"), scope);
    return p;
  }

  static State read(Path root) {
    return run(2000, root, "", -1, -1, READ, -1, NEVER);
  }

  static State set(Path root, State s, int side, int val, Writer w) {
    return run(2000, root, s.id, s.start, s.end, side, val, w);
  }

  static void check(boolean b) {
    if (!b) throw new AssertionError();
  }

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("rootward-limits");
    try {
      device(root, "usb", "USB", "System");
      Path mouse = device(root, "BAT2", "Battery", "Device");
      put(mouse.resolve(END_FILE), "100");
      Path aux = device(root, "headset", "Battery", null);
      put(aux.resolve(END_FILE), "100");
      check(read(root).name.isEmpty());
      Path pack = device(root, "pmic-main", "Battery", "System");
      Path upper = pack.resolve(END_FILE), lower = pack.resolve(START_FILE);
      put(upper, "80\n");
      State base = read(root);
      check(
          base.name.equals("pmic-main")
              && base.end == 80
              && base.start == MISSING
              && base.writable == END);
      check(run(0, root, base.id, -1, 80, END, 60, NEVER).id.isEmpty());
      check(set(root, base, START, 50, NEVER).writable == END);
      check(set(root, base, END, 101, NEVER).end == 80);
      check(set(root, base, END, 80, NEVER).end == 80);
      State rounded =
          set(
              root,
              base,
              END,
              76,
              (p, n) -> {
                check(p.equals(upper.toRealPath()) && n == 76);
                put(p, "75");
              });
      check(rounded.confirmedChange && rounded.end == 75 && rounded.note.contains("75%"));
      check(set(root, base, END, 70, NEVER).end == 75);
      put(lower, "50");
      base = read(root);
      check(base.writable == (END | START) && base.start == 50);
      check(
          set(root, base, START, 75, NEVER).start == 50
              && set(root, base, END, 40, NEVER).end == 75);
      check(
          set(
                  root,
                  base,
                  END,
                  76,
                  (p, n) -> {
                    throw new IOException();
                  })
              .note
              .contains("未获确认"));
      State peerChanged =
          set(
              root,
              base,
              END,
              79,
              (p, n) -> {
                put(p, "79");
                put(lower, "52");
              });
      check(!peerChanged.confirmedChange && peerChanged.end == 79 && peerChanged.start == 52 && peerChanged.note.contains("另一端"));
      put(lower, "invalid");
      check(read(root).writable == 0);
      put(lower, "55");
      put(upper, "50");
      check(read(root).writable == 0);
      put(upper, "80");
      Files.setPosixFilePermissions(lower, PosixFilePermissions.fromString("r--r--r--"));
      if (!Files.isWritable(lower)) check(read(root).writable == END && read(root).start == 55);
      Files.delete(lower);
      Files.createSymbolicLink(lower, upper);
      check(read(root).writable == 0);
      Files.delete(lower);
      Path extra = device(root, "BAT1", "Battery", null);
      check(read(root).name.isEmpty() && read(root).note.contains("多个"));
      put(extra.resolve("present"), "0");
      check(read(root).id.equals(SystemBattery.identity(pack.toRealPath())));
      Files.createSymbolicLink(root.resolve("alias"), pack);
      check(read(root).id.equals(SystemBattery.identity(pack.toRealPath())));
      put(pack.resolve("present"), "0");
      put(extra.resolve("present"), "1");
      put(extra.resolve(END_FILE), "80");
      check(
          set(root, peerChanged, END, 70, NEVER)
              .id
              .equals(SystemBattery.identity(extra.toRealPath())));
      check(
          !Files.exists(extra.resolve(START_FILE))
              && Files.readAllLines(mouse.resolve(END_FILE)).get(0).equals("100"));
      Files.delete(extra.resolve(END_FILE));
      put(extra.resolve(START_FILE), "45");
      State startOnly = read(root);
      check(startOnly.writable == START && startOnly.start == 45 && startOnly.end == MISSING);
      State changed = set(root, startOnly, START, 51, NativeLimits::submit);
      check(changed.confirmedChange && changed.start == 51 && changed.note.contains("确认"));
      System.out.println(
          "native threshold discovery, permission, validation and readback tests passed");
    } finally {
      try (Stream<Path> tree = Files.walk(root)) {
        for (Path p : tree.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
      }
    }
  }
}
