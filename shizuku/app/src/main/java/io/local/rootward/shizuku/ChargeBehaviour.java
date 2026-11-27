package io.local.rootward.shizuku;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

/** Fixed operations on the Linux power_supply charge_behaviour ABI. */
final class ChargeBehaviour {
  static final int PROBE = 0, PAUSE = 1, RESUME = 2, ACQUIRE = 3;
  static final int UNAVAILABLE = 0,
      AUTO = 1,
      INHIBITED = 2,
      DENIED = 3,
      EXTERNAL = 4,
      UNCONFIRMED = 5,
      INVALID = 6,
      ALREADY_HELD = 7;

  private static final Path SUPPLIES = Paths.get("/sys/class/power_supply");
  private static final String NORMAL = "auto", HOLD = "inhibit-charge";

  // The Binder endpoint supplies the UserService's actual process UID and a numeric operation.
  static final class Result {
    String id = "";
    int mode = UNAVAILABLE;
  }

  static synchronized Result operate(int uid, String expected, int action) {
    Result r = new Result();
    r.mode = mode(uid, action, SUPPLIES, ChargeBehaviour::submit, expected, r);
    return r;
  }

  interface ModeWriter {
    void write(Path node, boolean inhibit) throws IOException;
  }

  // The injectable root and driver write allow the same checks to run against a fake sysfs.
  static int apply(int uid, int action, Path supplies, ModeWriter writer) {
    Result r = new Result();
    return mode(uid, action, supplies, writer, null, r);
  }

  static int mode(
      int uid, int action, Path supplies, ModeWriter writer, String expected, Result r) {
    if (uid != 2000) return DENIED;
    if (action != PROBE && action != PAUSE && action != RESUME && action != ACQUIRE) return INVALID;
    try {
      Path supply = SystemBattery.select(supplies);
      if (supply == null) return UNAVAILABLE;
      Path device = supply.toRealPath();
      r.id = SystemBattery.identity(device);
      if (r.id.isEmpty()) return UNCONFIRMED;
      if (action != PROBE && expected != null && !r.id.equals(expected)) return UNCONFIRMED;
      Path node = device.resolve("charge_behaviour");
      if (!Files.readAttributes(node, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .isRegularFile()) return UNAVAILABLE;
      int current = parse(read(node));
      if (current == UNAVAILABLE) return UNAVAILABLE;

      // Opening without CREATE or TRUNCATE checks the shell's real open permission.
      // A probe never submits a value to the driver.
      try (OutputStream ignored = Files.newOutputStream(node, StandardOpenOption.WRITE)) {}
      if (action == PROBE || current == EXTERNAL) return current;
      if (action == ACQUIRE && current == INHIBITED) return ALREADY_HELD;
      int target = action == RESUME ? AUTO : INHIBITED;
      if (current == target) return current;
      try {
        if (!r.id.equals(SystemBattery.identity(device))) return UNCONFIRMED;
        writer.write(node, target == INHIBITED);
        return parse(read(node)) == target ? target : UNCONFIRMED;
      } catch (IOException | SecurityException lostAcknowledgement) {
        // Once submitted, even a read permission failure cannot prove the write was ignored.
        return UNCONFIRMED;
      }
    } catch (NoSuchFileException | SystemBattery.Ambiguous unavailable) {
      return UNAVAILABLE;
    } catch (AccessDeniedException | SecurityException denied) {
      return DENIED;
    } catch (IOException driverError) {
      return UNCONFIRMED;
    }
  }

  static void submit(Path node, boolean inhibit) throws IOException {
    try (OutputStream out = Files.newOutputStream(node, StandardOpenOption.WRITE)) {
      out.write(((inhibit ? HOLD : NORMAL) + "\n").getBytes(StandardCharsets.US_ASCII));
    }
  }

  private static String read(Path node) throws IOException {
    byte[] buffer = new byte[512];
    int count = 0;
    try (InputStream input = Files.newInputStream(node)) {
      while (count < buffer.length) {
        int size = input.read(buffer, count, buffer.length - count);
        if (size == -1) return new String(buffer, 0, count, StandardCharsets.US_ASCII).trim();
        count += size;
      }
    }
    return "";
  }

  // Linux prints all supported values and puts brackets around the currently selected one.
  private static int parse(String modes) {
    Set<String> options = new HashSet<>();
    String selected = null;
    for (String token : modes.split("[ \\t]+")) {
      boolean active = token.startsWith("[") && token.endsWith("]");
      String mode = active ? token.substring(1, token.length() - 1) : token;
      if (!mode.matches("[a-z][a-z0-9-]*") || !options.add(mode)) return UNAVAILABLE;
      if (active) {
        if (selected != null) return UNAVAILABLE;
        selected = mode;
      }
    }
    if (selected == null || !options.contains(NORMAL) || !options.contains(HOLD))
      return UNAVAILABLE;
    if (selected.equals(NORMAL)) return AUTO;
    if (selected.equals(HOLD)) return INHIBITED;
    return EXTERNAL;
  }
}
