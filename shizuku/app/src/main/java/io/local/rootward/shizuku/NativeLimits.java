package io.local.rootward.shizuku;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Independent, single-attribute operations on the standard Linux power_supply percentage limits.
 */
final class NativeLimits {
  static final int READ = 0, START = 1, END = 2;
  static final int MISSING = -1, UNREADABLE = -2;
  private static final String START_NODE = "charge_control_start_threshold";
  private static final String END_NODE = "charge_control_end_threshold";

  static final class State {
    String id = "", name = "", note = "";
    boolean confirmedChange;
    private Path device;
    int start = MISSING, end = MISSING, writable;

    boolean allowed(int side, int value) {
      return (side == START || side == END)
          && (writable & side) != 0
          && value >= 0
          && value <= 100
          && (side == START
              ? value < (end == MISSING ? 101 : end)
              : (start == MISSING || value > start) && start != UNREADABLE);
    }
  }

  interface Writer {
    void write(Path path, int value) throws IOException;
  }

  static void submit(Path path, int value) throws IOException {
    try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.WRITE)) {
      out.write((Integer.toString(value) + "\n").getBytes(StandardCharsets.US_ASCII));
    }
  }

  // No client-supplied identifier is ever interpreted as a filesystem path.
  static synchronized State run(
      int uid,
      Path supplies,
      String expectedId,
      int beforeStart,
      int beforeEnd,
      int side,
      int value,
      Writer write) {
    if (uid != 2000) return unavailable("仅支持已授权的 ADB shell。");
    try {
      Path supply = SystemBattery.select(supplies);
      if (supply == null) return unavailable("未识别到可确认的系统主电池。");
      State state = snapshot(supply);
      if (side == READ) return state;
      if (!state.id.equals(expectedId) || state.start != beforeStart || state.end != beforeEnd) {
        state.note = "电池或阈值已改变，请根据最新读数重新选择。";
        return state;
      }
      if (!state.allowed(side, value)) {
        state.note = "此端点不可写，或所选数值与当前电量区间冲突。";
        return state;
      }
      if ((side == START ? state.start : state.end) == value) {
        state.note = "驱动已使用这个值。";
        return state;
      }
      // Use the verified identity rather than the class alias, which can be rebound on hotplug.
      Path node = state.device.resolve(side == START ? START_NODE : END_NODE);
      if (!SystemBattery.identity(state.device).equals(state.id))
        return unavailable("电池已更换，请重新读取。");
      boolean accepted = true;
      try {
        write.write(node, value);
      } catch (IOException | SecurityException failed) {
        accepted = false;
      }
      Path after = SystemBattery.select(supplies);
      if (after == null || !SystemBattery.identity(after.toRealPath()).equals(state.id))
        return unavailable("写入后的电池身份无法确认，请重新读取。");
      State actual = snapshot(after);
      int back = side == START ? actual.start : actual.end;
      actual.confirmedChange =
          accepted
              && back >= 0
              && state.id.equals(actual.id)
              && (side == START
                  ? actual.end == state.end && back != state.start
                  : actual.start == state.start && back != state.end);
      if (!accepted || back < 0) actual.note = "写入未获确认，请核对当前驱动读数。";
      else if ((side == START ? actual.end != state.end : actual.start != state.start))
        actual.note = "驱动另一端的数值也发生了变化，请核对整个区间。";
      else if (back != value) actual.note = "请求 " + value + "%，驱动实际为 " + back + "%（可能取整或忽略）。";
      else actual.note = "驱动已回读确认 " + back + "%。";
      return actual;
    } catch (IOException | SecurityException e) {
      return unavailable(
          e instanceof SystemBattery.Ambiguous
              ? "发现多个系统电池，无法确认主电池。"
              : "电池目录不可用，或 ADB shell 无权读取。请检查系统充电设置。");
    }
  }

  private static State unavailable(String note) {
    State s = new State();
    s.note = note;
    return s;
  }

  private static State snapshot(Path supply) throws IOException {
    State s = new State();
    s.device = supply.toRealPath();
    s.id = SystemBattery.identity(s.device);
    s.name = supply.getFileName().toString();
    s.start = limit(s.device.resolve(START_NODE), s, START);
    s.end = limit(s.device.resolve(END_NODE), s, END);
    if (s.id.isEmpty()) s.note = "系统未提供稳定的电池标识，仅显示原生阈值。";
    if (s.id.isEmpty() || s.start == UNREADABLE
        || s.end == UNREADABLE
        || (s.start >= 0 && s.end >= 0 && s.start >= s.end)) s.writable = 0;
    return s;
  }

  private static int limit(Path node, State s, int side) {
    try {
      if (!Files.readAttributes(node, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .isRegularFile()) return UNREADABLE;
      String raw = SystemBattery.text(node);
      if (!raw.matches("0|[1-9][0-9]?|100")) return UNREADABLE;
      try (OutputStream ignored = Files.newOutputStream(node, StandardOpenOption.WRITE)) {
        s.writable |= side;
      } catch (IOException | SecurityException readOnly) {
        // Keep the value visible even if the service cannot open it for writing.
      }
      return Integer.parseInt(raw);
    } catch (NoSuchFileException absent) {
      return MISSING;
    } catch (IOException | SecurityException invalid) {
      return UNREADABLE;
    }
  }
}
