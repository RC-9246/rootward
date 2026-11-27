package io.local.rootward.shizuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import java.util.UUID;

/** One reversible, acknowledged change. A durable pending marker survives a lost Binder reply. */
final class NativeLimitJournal {
  private static final int NONE = 0, PENDING = 1, READY = 2;
  private static final String PROCESS = UUID.randomUUID().toString();

  interface Driver {
    Bundle call(int side, int value) throws RemoteException;
  }

  static synchronized Bundle transact(
      Context app, Bundle before, int side, int value, boolean undo, Driver driver)
      throws RemoteException {
    SharedPreferences log = app.getSharedPreferences("native_limit_undo", Context.MODE_PRIVATE);
    String boot = boot(app);
    if (side == NativeLimits.READ) return describe(log, boot, driver.call(side, value));

    // The dialog may have been left open or a different activity may have replaced its undo entry.
    Bundle current = driver.call(NativeLimits.READ, 0);
    if (!sameValues(current, before) || (current.getInt("writable") & side) == 0) {
      current.putString("note", "电池、阈值或权限已变化，请根据最新读数重新选择。");
      return describe(log, boot, current);
    }
    if (undo
        && (!eligible(log, boot, current)
            || side != log.getInt("side", 0)
            || value != log.getInt(side == NativeLimits.START ? "beforeStart" : "beforeEnd", -1)
            || !log.getString("token", "").equals(before.getString("undoToken", "")))) {
      current.putString("note", "上次变更记录或驱动状态已变化，撤销请求已取消。");
      return describe(log, boot, current);
    }
    if (current.getInt(side == NativeLimits.START ? "start" : "end") == value) {
      current.putString("note", "驱动已使用这个值。");
      return describe(log, boot, current);
    }
    SharedPreferences.Editor edit = log.edit().putInt("state", PENDING);
    if (!undo) {
      edit.putString("id", current.getString("id"))
          .putString("boot", boot)
          .putString("token", UUID.randomUUID().toString())
          .putInt("side", side)
          .putInt("beforeStart", current.getInt("start"))
          .putInt("beforeEnd", current.getInt("end"))
          .remove("afterStart")
          .remove("afterEnd");
    }
    if (!edit.commit()) {
      current.putString("note", "无法保存撤销记录，本次设置已取消。");
      return describe(log, boot, current);
    }

    Bundle after = driver.call(side, value); // The UserService checks both original values again.
    if (after.getBoolean("confirmedChange")
        && after.getString("id", "").equals(current.getString("id"))) {
      if (!undo) {
        if (!log.edit()
            .putInt("state", READY)
            .putInt("afterStart", after.getInt("start"))
            .putInt("afterEnd", after.getInt("end"))
            .commit()) after.putString("note", "驱动已改变，撤销记录保存失败。请核对当前值。");
      } else if (valuesMatch(log, after, "before")) {
        if (log.edit().clear().commit()) after.putString("note", "已撤销上一次修改，驱动读回的两个端点均与修改前一致。");
        else after.putString("note", "两个端点已恢复，撤销记录未能清除。");
      } else {
        after.putString("note", "驱动未恢复到原值，请核对实际区间。");
      }
    }
    return describe(log, boot, after);
  }

  private static boolean sameValues(Bundle a, Bundle b) {
    return !a.getString("id", "").isEmpty()
        && a.getString("id").equals(b.getString("id", ""))
        && a.getInt("start") == b.getInt("start")
        && a.getInt("end") == b.getInt("end");
  }

  private static boolean valuesMatch(SharedPreferences p, Bundle state, String prefix) {
    return p.getInt(prefix + "Start", Integer.MIN_VALUE) == state.getInt("start", -1)
        && p.getInt(prefix + "End", Integer.MIN_VALUE) == state.getInt("end", -1);
  }

  private static boolean eligible(SharedPreferences p, String boot, Bundle state) {
    int side = p.getInt("side", 0);
    return p.getInt("state", NONE) == READY
        && p.getString("boot", "").equals(boot)
        && p.getString("id", "").equals(state.getString("id", ""))
        && (state.getInt("writable") & side) != 0
        && valuesMatch(p, state, "after");
  }

  private static Bundle describe(SharedPreferences p, String boot, Bundle state) {
    int status = p.getInt("state", NONE);
    if (status == NONE) return state;
    int side = p.getInt("side", 0);
    int prior = p.getInt(side == NativeLimits.START ? "beforeStart" : "beforeEnd", -1);
    String detail = "上次修改前的" + (side == NativeLimits.START ? "下限" : "上限") + "为 " + prior + "%。";
    if (eligible(p, boot, state)) {
      state.putInt("undoSide", side);
      state.putInt("undoValue", prior);
      state.putString("undoToken", p.getString("token", ""));
    } else {
      detail +=
          status == PENDING ? " 上次操作或关联端点未获完整确认，无法自动撤销。" : " 当前电池身份、启动周期、端点读数或权限与记录不符，无法自动撤销。";
    }
    state.putString("undoDetail", detail);
    return state;
  }

  private static String boot(Context app) {
    try {
      int count = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
      if (count >= 0) return "boot-" + count;
    } catch (SecurityException ignored) {
    }
    return PROCESS;
  }
}
