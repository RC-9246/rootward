package io.local.rootward.shizuku;

import static io.local.rootward.shizuku.ChargeBehaviour.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/** Runs the production access/protocol checks on a temporary power_supply tree. */
public final class ChargeBehaviourTest {
  private static final String AUTO_MODES = "[auto] inhibit-charge force-discharge\n";
  private static final String HELD_MODES = "auto [inhibit-charge] force-discharge\n";
  private static final ModeWriter NEVER_WRITE =
      (p, hold) -> {
        throw new AssertionError("unexpected driver write: " + p);
      };

  private static void put(Path file, String text) throws IOException {
    Files.write(file, text.getBytes(StandardCharsets.US_ASCII));
  }

  private static String contents(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static void expect(int expected, int actual, String message) {
    check(expected == actual, message + ": expected " + expected + ", got " + actual);
  }

  private static void test(Path root) throws IOException {
    Path battery = Files.createDirectory(root.resolve("battery"));
    Path type = battery.resolve("type"), node = battery.resolve("charge_behaviour");
    put(type, "Battery\n");
    expect(UNAVAILABLE, apply(2000, PROBE, root, NEVER_WRITE), "missing control");
    check(!Files.exists(node), "did not create a missing control");
    put(node, AUTO_MODES);
    for (int uid : new int[] {0, 1000, 10298}) {
      expect(DENIED, apply(uid, PAUSE, root, NEVER_WRITE), "refuse non-shell UID " + uid);
    }
    expect(INVALID, apply(2000, -1, root, NEVER_WRITE), "reject invalid operation");
    expect(INVALID, apply(2000, 4, root, NEVER_WRITE), "reject extra operation");
    put(type, "USB\n");
    expect(UNAVAILABLE, apply(2000, PAUSE, root, NEVER_WRITE), "reject non-battery");
    put(type, "Battery\n");

    expect(AUTO, apply(2000, PROBE, root, NEVER_WRITE), "probe does not change auto");
    expect(AUTO, apply(2000, RESUME, root, NEVER_WRITE), "already auto");
    check(contents(node).equals(AUTO_MODES), "probe must open without truncating");
    ModeWriter kernel =
        (file, hold) -> {
          check(node.equals(file), "writes only the expected battery attribute");
          ChargeBehaviour.submit(file, hold);
          String expected = hold ? "inhibit-charge\n" : "auto\n";
          check(contents(file).startsWith(expected), "actual bytes written to the driver");
          // A regular file retains bytes after the command. Sysfs instead calls the driver's
          // store() and reconstructs the complete list of modes on the next show().
          put(file, hold ? HELD_MODES : AUTO_MODES);
        };
    expect(INHIBITED, apply(2000, PAUSE, root, kernel), "pause verified by driver read-back");
    expect(INHIBITED, apply(2000, PAUSE, root, NEVER_WRITE), "already paused");
    expect(ALREADY_HELD, apply(2000, ACQUIRE, root, NEVER_WRITE), "do not claim existing hold");
    expect(INHIBITED, apply(2000, PROBE, root, NEVER_WRITE), "probe self or external hold");
    expect(AUTO, apply(2000, RESUME, root, kernel), "explicit release verified by read-back");
    int phase = ChargePhase.FILL;
    boolean owned = false;
    for (int level : new int[] {67, 75, 74, 69, 63, 51, 50, 55, 75}) {
      if (level == 63) put(node, AUTO_MODES); // Simulate a driver reset on cable insertion.
      phase = ChargePhase.sample(phase, level, 50, 75);
      int observed = apply(2000, PROBE, root, NEVER_WRITE);
      if (observed == AUTO) owned = false;
      int command =
          HardwareCruise.operation(
              true,
              observed,
              owned ? HardwareCruise.Hold.OWNED : HardwareCruise.Hold.NONE,
              phase,
              level,
              300,
              false);
      if (command != HardwareCruise.NONE) {
        int confirmed = apply(2000, command, root, kernel);
        expect(command == RESUME ? AUTO : INHIBITED, confirmed, "sysfs cruise at " + level);
        owned = confirmed == INHIBITED;
      }
      expect(
          phase == ChargePhase.WAIT ? INHIBITED : AUTO,
          apply(2000, PROBE, root, NEVER_WRITE),
          "confirmed hardware mode at " + level);
    }
    expect(AUTO, apply(2000, RESUME, root, kernel), "release the test driver's own hold");
    expect(UNCONFIRMED, apply(2000, PAUSE, root, (p, hold) -> {}), "driver ignores write");
    expect(
        UNCONFIRMED,
        apply(
            2000,
            PAUSE,
            root,
            (p, hold) -> {
              throw new IOException("driver rejects this write");
            }),
        "driver rejects a write despite successful open");
    for (String external : new String[] {"force-discharge", "inhibit-charge-awake", "oem-mode"}) {
      put(node, "auto inhibit-charge [" + external + "]\n");
      expect(EXTERNAL, apply(2000, PROBE, root, NEVER_WRITE), "detect external mode");
      expect(EXTERNAL, apply(2000, RESUME, root, NEVER_WRITE), "preserve external mode on release");
      expect(EXTERNAL, apply(2000, PAUSE, root, NEVER_WRITE), "preserve external mode on pause");
    }
    for (String invalid :
        new String[] {
          "auto",
          "[auto]",
          "inhibit-charge auto",
          "[auto] [inhibit-charge]",
          "[auto] inhibit-charge auto",
          "[auto inhibit-charge",
          "[auto] inhibit-charge\nmalformed",
          "[auto] inhibit-charge/extra",
          "[]"
        }) {
      put(node, invalid);
      expect(UNAVAILABLE, apply(2000, PAUSE, root, NEVER_WRITE), "invalid ABI " + invalid);
    }
    put(node, AUTO_MODES);
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(node);
    try {
      Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("r--r--r--"));
      // In root-run CI file mode bits cannot express the shell's permission boundary.
      if (!Files.isWritable(node)) {
        expect(DENIED, apply(2000, PROBE, root, NEVER_WRITE), "read-only driver");
        expect(DENIED, apply(2000, PAUSE, root, NEVER_WRITE), "read-only write rejected");
      }
      Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("-w-------"));
      if (!Files.isReadable(node))
        expect(DENIED, apply(2000, PAUSE, root, NEVER_WRITE), "write-only driver");
    } finally {
      Files.setPosixFilePermissions(node, permissions);
    }
    expect(
        UNCONFIRMED,
        apply(
            2000,
            PAUSE,
            root,
            (p, hold) -> {
              throw new java.nio.file.AccessDeniedException(p.toString());
            }),
        "permission lost while submitting or acknowledging a write");

    Path arbitrary = root.resolve("some-charge-knob");
    Files.move(node, arbitrary);
    Files.createSymbolicLink(node, arbitrary);
    expect(UNAVAILABLE, apply(2000, PAUSE, root, NEVER_WRITE), "do not follow leaf aliases");
    check(contents(arbitrary).equals(AUTO_MODES), "alternate attribute untouched");
    Files.delete(node);
    Path usb = Files.createDirectory(root.resolve("usb"));
    put(usb.resolve("type"), "Battery");
    put(usb.resolve("charge_behaviour"), AUTO_MODES);
    expect(UNAVAILABLE, apply(2000, PAUSE, root, NEVER_WRITE), "only fixed battery names");
    Path alternate = Files.createDirectory(root.resolve("BAT0"));
    put(alternate.resolve("type"), "Battery");
    put(alternate.resolve("charge_behaviour"), AUTO_MODES);
    expect(AUTO, apply(2000, PROBE, root, NEVER_WRITE), "BAT0 fixed fallback");
  }

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("rootward-power-supply");
    try {
      test(root);
    } finally {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))
          Files.delete(file);
      }
    }
    System.out.println("charge_behaviour fake sysfs tests passed");
  }
}
