package io.local.rootward.shizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Foreground diagnostic and explicit manual actions on a verified ADB UserService session. */
final class ShellLink {
  interface View {
    void show(String detail, String action, boolean enabled);
  }

  private final Context context;
  private final SharedPreferences prefs;
  private final View view;
  private final Shizuku.UserServiceArgs args;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private IBatteryReader reader;
  private IBinder server;
  private ServiceConnection connection;
  private boolean binding, closed, busy;
  private String report, message;
  private String observedBattery = "";
  private int observed = ChargeBehaviour.UNAVAILABLE;
  private volatile int session;

  private final Runnable timeout =
      () -> {
        if (binding && !closed) {
          release();
          message = "驱动服务启动超时，可重试。";
          refresh();
        }
      };
  private final Shizuku.OnBinderReceivedListener connected = this::refresh;
  private final Shizuku.OnBinderDeadListener dead =
      () -> {
        if (!closed) {
          release();
          refresh();
        }
      };
  private final Shizuku.OnRequestPermissionResultListener permission =
      (id, res) -> {
        if (!closed && id == 400) {
          if (res == PackageManager.PERMISSION_GRANTED) start();
          else {
            release();
            message = "授权已拒绝，可到 Shizuku 的授权应用管理中修改。";
            refresh();
          }
        }
      };

  ShellLink(Activity activity, View view) {
    context = activity.getApplicationContext();
    prefs = ObserverApp.prefs(context);
    this.view = view;
    args =
        new Shizuku.UserServiceArgs(new ComponentName(activity, BatteryReader.class))
            .tag("rootward-diagnostics")
            .version(3)
            .daemon(false)
            .processNameSuffix("battery_reader");
  }

  void open() {
    Shizuku.addBinderReceivedListenerSticky(connected, main);
    Shizuku.addBinderDeadListener(dead, main);
    Shizuku.addRequestPermissionResultListener(permission);
    refresh();
  }

  private static boolean live(IBinder gateway, IBatteryReader service) {
    try {
      return gateway != null
          && Shizuku.getBinder() == gateway
          && gateway.pingBinder()
          && ObserverApp.shellPermission()
          && Shizuku.getUid() == 2000
          && service != null
          && service.asBinder().pingBinder();
    } catch (RuntimeException e) {
      return false;
    }
  }

  int mode() {
    return live(server, reader) ? observed : ChargeBehaviour.UNAVAILABLE;
  }

  boolean canWrite() {
    int state = mode();
    return !closed
        && !busy
        && !prefs.getBoolean("cruise", false)
        && (state == ChargeBehaviour.AUTO || state == ChargeBehaviour.INHIBITED);
  }

  private static String describe(int state) {
    switch (state) {
      case ChargeBehaviour.AUTO:
        return "驱动为系统自动充电（auto）。";
      case ChargeBehaviour.INHIBITED:
        return "驱动已暂停充电（inhibit-charge）。";
      case ChargeBehaviour.DENIED:
        return "ADB shell 无权读写标准充电模式。";
      case ChargeBehaviour.EXTERNAL:
        return "驱动已选择其他模式，保留当前设置。";
      case ChargeBehaviour.UNCONFIRMED:
        return "驱动结果未确认，请刷新后核对当前模式。";
      default:
        return "未发现可控的标准充电模式，可使用记录与提醒。";
    }
  }

  void refresh() {
    if (closed) return;
    try {
      if (!Shizuku.pingBinder()) {
        release();
        view.show("在 Shizuku 中通过无线调试或已配对电脑启动服务。系统电池读数与提醒可独立使用。", "检查连接", true);
        return;
      }
      if (server != null && Shizuku.getBinder() != server) release();
      if (Shizuku.getVersion() < 11) {
        release();
        view.show("需要支持授权请求和用户服务的 Shizuku。请更新管理器。", "检查连接", true);
        return;
      }
      if (!ObserverApp.shellPermission()) {
        if (server != null) release();
        view.show(message != null ? message : "授权后可读取驱动与 USB 测点，检查此机型是否支持充电控制。", "授权并读取驱动", true);
        return;
      }
      if (Shizuku.getUid() != 2000) {
        release();
        view.show("请以无线调试或电脑 ADB 方式启动 Shizuku，以获得 ADB shell 身份。", "检查连接", true);
        return;
      }
      if (reader != null && !reader.asBinder().pingBinder()) {
        release();
        message = "驱动服务已断开，请重新读取。";
      }
      String detail = message != null ? message : binding ? "正在连接驱动服务…" : "已获 shell 授权，可读取驱动。";
      if (report != null) detail += "\n" + report;
      view.show(detail, "刷新驱动读数", !binding && !busy);
    } catch (RuntimeException e) {
      release();
      view.show("Shizuku 暂不可用，请检查服务后重试。", "检查连接", true);
    }
  }

  void act() {
    if (closed || binding || busy) return;
    try {
      if (!Shizuku.pingBinder() || Shizuku.getVersion() < 11) {
        refresh();
        return;
      }
      if (!ObserverApp.shellPermission()) {
        Shizuku.requestPermission(400);
        return;
      }
      start();
    } catch (RuntimeException e) {
      release();
      message = "无法请求 Shizuku 服务。";
      refresh();
    }
  }

  private void start() {
    if (closed || binding || busy) return;
    try {
      if (!Shizuku.pingBinder() || !ObserverApp.shellPermission() || Shizuku.getUid() != 2000) {
        refresh();
        return;
      }
      if (live(server, reader)) {
        run(ChargeBehaviour.PROBE);
        return;
      }
      release();
      server = Shizuku.getBinder();
      binding = true;
      connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              main.post(
                  () -> {
                    if (closed || connection != this) return;
                    binding = false;
                    main.removeCallbacks(timeout);
                    reader = IBatteryReader.Stub.asInterface(b);
                    if (live(server, reader)) run(ChargeBehaviour.PROBE);
                    else disconnected(this);
                  });
            }

            public void onServiceDisconnected(ComponentName n) {
              main.post(() -> disconnected(this));
            }
          };
      Shizuku.bindUserService(args, connection);
      main.postDelayed(timeout, 12000);
      refresh();
    } catch (RuntimeException e) {
      release();
      message = "驱动服务启动失败，请确认授权并重试。";
      refresh();
    }
  }

  private void disconnected(ServiceConnection old) {
    if (closed || connection != old) return;
    release();
    message = "驱动服务已断开，请重新读取。";
    refresh();
  }

  interface LimitsResult {
    void accept(android.os.Bundle result);
  }

  void nativeLimit(
      android.os.Bundle before, int side, int value, boolean undo, LimitsResult callback) {
    final int generation = session;
    final IBinder gateway = server;
    final IBatteryReader target = reader;
    final boolean change = side != NativeLimits.READ;
    final long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
    if (closed
        || busy
        || !live(gateway, target)
        || (change
            && (before.getInt("session", -1) != generation || prefs.getBoolean("cruise", false)))) {
      callback.accept(null);
      return;
    }
    busy = true;
    refresh();
    worker.execute(
        () -> {
          android.os.Bundle result = null;
          try {
            result =
                NativeLimitJournal.transact(
                    context,
                    before,
                    side,
                    value,
                    undo,
                    (endpoint, percent) -> {
                      if (generation != session
                          || !live(gateway, target)
                          || (endpoint != NativeLimits.READ && prefs.getBoolean("cruise", false)))
                        throw new RemoteException();
                      return target.nativeLimit(
                          before.getString("id", ""),
                          before.getInt("start", -1),
                          before.getInt("end", -1),
                          endpoint,
                          percent,
                          deadline);
                    });
          } catch (RemoteException | RuntimeException ignored) {
            // Report missing acknowledgement even when the driver might have accepted the change.
          }
          final android.os.Bundle answer = result;
          main.post(
              () -> {
                if (closed) return;
                if (generation != session) {
                  callback.accept(null);
                  return;
                }
                busy = false;
                refresh();
                if (answer != null) answer.putInt("session", generation);
                callback.accept(live(gateway, target) ? answer : null);
              });
        });
  }

  void setPaused(boolean pause) {
    if (!canWrite()) {
      refresh();
      return;
    }
    run(pause ? ChargeBehaviour.PAUSE : ChargeBehaviour.RESUME);
  }

  private void run(int action) {
    final IBatteryReader target = reader;
    final IBinder gateway = server;
    final int generation = session;
    final int expected = observed;
    final String expectedBattery = observedBattery;
    busy = true;
    observed = ChargeBehaviour.UNAVAILABLE;
    message = action == ChargeBehaviour.PROBE ? "正在读取驱动…" : "正在核对驱动并回读操作结果…";
    refresh();
    worker.execute(
        () -> {
          try {
            if (generation != session || !live(gateway, target)) throw new RemoteException();
            String snapshot = action == ChargeBehaviour.PROBE ? target.readSnapshot() : null;
            android.os.Bundle readMode = target.chargeMode("", ChargeBehaviour.PROBE);
            String battery = readMode.getString("id", "");
            int result = readMode.getInt("mode", ChargeBehaviour.UNCONFIRMED);
            String note = null;
            boolean resumed = false;
            if (action != ChargeBehaviour.PROBE) {
              if (result != expected || !battery.equals(expectedBattery)) {
                note = "驱动状态已变化，请核对后再操作。";
              } else if (prefs.getBoolean("cruise", false)) {
                note = "巡航已接管驱动，请关闭巡航后手动操作。";
              } else {
                if (generation != session || !live(gateway, target)) throw new RemoteException();
                // The UserService checks its process UID and reads the node back after each write.
                result =
                    target.chargeMode(battery, action).getInt("mode", ChargeBehaviour.UNCONFIRMED);
                resumed = action == ChargeBehaviour.RESUME && result == ChargeBehaviour.AUTO;
              }
            }
            final int actual = result;
            final String notice = note;
            final boolean confirmedResume = resumed;
            main.post(
                () -> {
                  if (closed || generation != session) return;
                  if (!live(gateway, target)) {
                    disconnected(connection);
                    return;
                  }
                  busy = false;
                  observed = actual;
                  observedBattery = battery;
                  if (snapshot != null) report = snapshot;
                  message = (notice == null ? "" : notice + " ") + describe(actual);
                  if (!prefs.getBoolean("cruise", false)
                      && (actual == ChargeBehaviour.AUTO || actual == ChargeBehaviour.EXTERNAL))
                    recordRelease(confirmedResume);
                  refresh();
                });
          } catch (RemoteException | RuntimeException e) {
            main.post(
                () -> {
                  if (closed || generation != session) return;
                  release();
                  message =
                      action == ChargeBehaviour.PROBE ? "读取失败，请重新连接。" : "驱动未返回确认，充电模式可能已改变。请重新读取。";
                  refresh();
                });
          }
        });
  }

  private void recordRelease(boolean manualResume) {
    boolean pending =
        prefs.getBoolean("cruiseOwned", false) || prefs.getBoolean("cruiseUncertain", false);
    if (!pending && !manualResume) return;
    SharedPreferences.Editor edit =
        prefs.edit().putBoolean("cruiseOwned", false).putBoolean("cruiseUncertain", false);
    if (manualResume
        && prefs.getBoolean("watch", false)
        && prefs.getInt("phase", ChargePhase.FILL) == ChargePhase.WAIT) {
      Reading reading = Reading.now(context);
      if (reading != null
          && reading.level > prefs.getInt("low", 50)
          && reading.level < prefs.getInt("high", 75)) edit.putInt("phase", ChargePhase.MANUAL);
    }
    edit.apply();
  }

  private void release() {
    ++session;
    ServiceConnection old = connection;
    connection = null;
    server = null;
    reader = null;
    binding = busy = false;
    observed = ChargeBehaviour.UNAVAILABLE;
    report = message = null;
    main.removeCallbacks(timeout);
    if (old != null) {
      try {
        Shizuku.unbindUserService(args, old, true);
      } catch (RuntimeException ignored) {
      }
    }
  }

  void close() {
    closed = true;
    release();
    worker.shutdownNow();
    Shizuku.removeBinderReceivedListener(connected);
    Shizuku.removeBinderDeadListener(dead);
    Shizuku.removeRequestPermissionResultListener(permission);
  }
}
