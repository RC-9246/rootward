package io.local.rootward.shizuku;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import io.local.rootward.shizuku.HardwareCruise.Hold;
import java.util.UUID;
import rikka.shizuku.Shizuku;

/** One serial controller for the opt-in foreground service. It never requests permissions. */
final class CruiseDriver {
  private static final String PROCESS_SESSION = UUID.randomUUID().toString();
  private final Context context;
  private final SharedPreferences prefs;
  private final HandlerThread thread = new HandlerThread("rootward-cruise");
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Handler worker;
  private final Shizuku.UserServiceArgs args;
  private final Runnable poll = this::evaluate;
  private final Runnable timeout = this::bindingTimedOut;
  private IBatteryReader reader;
  private ServiceConnection connection;
  private volatile boolean closed;
  private volatile int stopSequence;
  private Runnable stopped;

  CruiseDriver(Context context) {
    this.context = context.getApplicationContext();
    prefs = ObserverApp.prefs(context);
    args =
        new Shizuku.UserServiceArgs(new ComponentName(context, BatteryReader.class))
            .tag("rootward-cruise")
            .version(5)
            .daemon(false)
            .processNameSuffix("battery_cruise");
    thread.start();
    worker = new Handler(thread.getLooper());
  }

  void sample() {
    if (!closed) {
      worker.removeCallbacks(poll);
      worker.post(poll);
    }
  }

  /** Normal stop gets a bounded opportunity to release our confirmed pause. */
  void stop(Runnable done) {
    if (stopped != null) {
      stopped = done;
      return;
    }
    prefs.edit().putBoolean("cruise", false).apply();
    ++stopSequence;
    stopped = done;
    main.postDelayed(finishStop, 9000);
    sample();
  }

  private final Runnable finishStop =
      () -> {
        Runnable done = stopped;
        stopped = null;
        if (done != null) {
          if (!enabled() && pendingHold()) problem("巡航已关闭。上次停充尚未确认解除；请连接 Shizuku 后检查驱动。");
          done.run();
        }
      };

  private void evaluated(int sequence) {
    main.post(
        () -> {
          if (stopped != null && sequence == stopSequence) {
            main.removeCallbacks(finishStop);
            finishStop.run();
          }
        });
  }

  private boolean enabled() {
    return prefs.getBoolean("cruise", false) && prefs.getBoolean("watch", false);
  }

  private boolean pendingHold() {
    return prefs.getBoolean("cruiseOwned", false) || prefs.getBoolean("cruiseUncertain", false);
  }

  private void status(String message) {
    if (!message.equals(prefs.getString("cruiseStatus", ""))) {
      prefs.edit().putString("cruiseStatus", message).apply();
      context.getSystemService(NotificationManager.class).cancel(34);
      context.sendBroadcast(new Intent(WatchService.UPDATE).setPackage(context.getPackageName()));
    }
  }

  private void problem(String message) {
    if (message.equals(prefs.getString("cruiseStatus", ""))) return;
    status(message);
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            34,
            new Intent(context, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    try {
      context
          .getSystemService(NotificationManager.class)
          .notify(
              34,
              WatchNotices.builder(context, WatchService.ALERT)
                  .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                  .setContentTitle("归根硬件巡航需要检查")
                  .setContentText(message)
                  .setStyle(new Notification.BigTextStyle().bigText(message))
                  .setContentIntent(open)
                  .setAutoCancel(true)
                  .build());
    } catch (SecurityException notificationsDisabled) {
      // The same persistent status is visible in the app.
    }
  }

  private void unavailable(String why, int sequence) {
    problem(why + (pendingHold() ? " 上次停充可能仍有效，请重连检查。" : " 暂时仅记录与提醒，无法保证在阈值动作。"));
    releaseConnection();
    evaluated(sequence);
  }

  private void evaluate() {
    if (closed) return;
    int sequence = stopSequence;
    try {
      if (!enabled() && !pendingHold()) {
        status("硬件巡航已关闭。");
        releaseConnection();
        evaluated(sequence);
        return;
      }
      if (!Shizuku.pingBinder() || Shizuku.getUid() != 2000 || !ObserverApp.shellPermission()) {
        unavailable("Shizuku 的 ADB shell 连接或授权已中断。", sequence);
        return;
      }
      if (reader == null) {
        if (connection == null) bind();
        return;
      }
      syncDriver();
      evaluated(sequence);
    } catch (RemoteException | RuntimeException failure) {
      unavailable("驱动服务连接失败。", sequence);
    }
  }

  private void bind() {
    ServiceConnection attempt =
        new ServiceConnection() {
          public void onServiceConnected(ComponentName name, IBinder binder) {
            worker.post(
                () -> {
                  if (closed || connection != this) return;
                  worker.removeCallbacks(timeout);
                  reader = IBatteryReader.Stub.asInterface(binder);
                  evaluate();
                });
          }

          public void onServiceDisconnected(ComponentName name) {
            worker.post(
                () -> {
                  if (!closed && connection == this) unavailable("驱动服务连接已断开。", stopSequence);
                });
          }
        };
    connection = attempt;
    Shizuku.bindUserService(args, attempt);
    worker.postDelayed(timeout, 7000);
  }

  private void bindingTimedOut() {
    if (!closed && reader == null) unavailable("连接驱动服务超时。", stopSequence);
  }

  private void releaseConnection() {
    ServiceConnection old = connection;
    connection = null;
    reader = null;
    worker.removeCallbacks(timeout);
    if (old != null) {
      try {
        Shizuku.unbindUserService(args, old, true);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void syncDriver() throws RemoteException {
    android.os.Bundle sample = reader.chargeMode("", ChargeBehaviour.PROBE);
    int observed = sample.getInt("mode", ChargeBehaviour.UNCONFIRMED);
    String driver = sample.getString("id", "");
    Hold previous =
        prefs.getBoolean("cruiseUncertain", false)
            ? Hold.UNCERTAIN
            : prefs.getBoolean("cruiseOwned", false) ? Hold.OWNED : Hold.NONE;
    String boot = currentBoot();
    // Retain ownership across a temporary missing/denied probe; reconcile only a known driver mode.
    boolean identified =
        !driver.isEmpty()
            && (observed == ChargeBehaviour.AUTO
                || observed == ChargeBehaviour.INHIBITED
                || observed == ChargeBehaviour.EXTERNAL);
    Hold hold =
        !identified
            ? previous
            : previous.reconcile(
                observed,
                boot.equals(prefs.getString("cruiseBoot", ""))
                    && !driver.isEmpty()
                    && driver.equals(prefs.getString("cruiseBattery", "")));
    if (hold != previous && !claim(hold, boot, driver)) return;
    if (observed != ChargeBehaviour.AUTO && observed != ChargeBehaviour.INHIBITED) {
      problem(
          (observed == ChargeBehaviour.EXTERNAL ? "系统已选择其他驱动模式。" : "驱动未确认可读写的标准充电模式。")
              + (pendingHold() ? " 上次停充尚待确认；请检查驱动。" : " 暂时仅记录与提醒。"));
      return;
    }
    if (hold == Hold.UNCERTAIN) {
      problem("先前的停充写入或归属未确认。请关闭巡航，检查驱动后手动恢复系统控制。");
      return;
    }

    boolean active = enabled();
    Reading r = active ? Reading.now(context) : null;
    int level = r == null ? -1 : r.level, temp = r == null ? Integer.MIN_VALUE : r.temp;
    int phase =
        ChargePhase.sample(
            prefs.getInt("phase", ChargePhase.FILL),
            level,
            prefs.getInt("low", 50),
            prefs.getInt("high", 75));
    boolean hot =
        HardwareCruise.hot(prefs.getBoolean("thermal", false), temp, prefs.getInt("heat", 40));
    boolean fault = r == null ? prefs.getBoolean(Reading.HEALTH_HOLD, false) : r.healthHold(prefs);
    boolean scheduled = BoostWindow.permits(
        BoostPlan.stage(prefs, System.currentTimeMillis(), phase, level), level,
        prefs.getInt("low", 50), prefs.getInt("high", 75), temp, hot || fault);
    if (scheduled) phase = ChargePhase.FILL;
    int operation = HardwareCruise.operation(active, observed, hold, phase, level, temp, hot || fault);
    if (operation == HardwareCruise.NONE) {
      if (observed == ChargeBehaviour.INHIBITED && hold != Hold.OWNED)
        problem("检测到既有停充。归根保留原有设置；需要时请关闭巡航后手动恢复。");
      else if (observed == ChargeBehaviour.INHIBITED)
        status(fault ? "归根已暂停充电，等待系统报告电池健康正常。"
            : hot ? "归根已暂停充电，等待温度回落。" : "归根已暂停充电，等待恢复条件。");
      else status(active ? "驱动处于系统自动充电模式，巡航正在监测。" : "硬件巡航已关闭。");
      return;
    }
    if (closed || active != enabled()) {
      sample();
      return;
    }
    if (!ObserverApp.shellPermission()) {
      problem("Shizuku 授权已中断。请重新连接后核对充电模式。");
      return;
    }
    if (operation == ChargeBehaviour.ACQUIRE) {
      // A process death or a lost Binder reply leaves an explicit ambiguous marker.
      if (!claim(Hold.UNCERTAIN, boot, driver)) return;
      int after = reader.chargeMode(driver, operation).getInt("mode", ChargeBehaviour.UNCONFIRMED);
      if (after == ChargeBehaviour.INHIBITED) {
        if (claim(Hold.OWNED, boot, driver)) status(fault ? "驱动已确认因系统电池健康异常暂停充电。"
            : hot ? "驱动已确认因温度暂停充电。" : "驱动已确认巡航暂停充电。");
      } else if (after == ChargeBehaviour.UNCONFIRMED) {
        problem("暂停请求未获驱动确认。请检查驱动并按需手动恢复系统控制。");
      } else if (claim(Hold.NONE, boot, driver)) {
        problem("驱动未接受归根停充。已有的模式保持原样。");
      }
    } else {
      if (scheduled && BoostPlan.stage(prefs, System.currentTimeMillis(),
          prefs.getInt("phase", ChargePhase.FILL), level) != BoostWindow.Stage.OPEN) {
        sample();
        return;
      }
      int after =
          reader
              .chargeMode(driver, ChargeBehaviour.RESUME)
              .getInt("mode", ChargeBehaviour.UNCONFIRMED);
      if (after == ChargeBehaviour.AUTO) {
        if (claim(Hold.NONE, boot, driver))
          status(active ? "驱动已恢复系统自动充电；系统阈值和温控仍生效。" : "已解除归根停充，并关闭硬件巡航。");
      } else problem("解除归根停充尚未确认；重连后将再次核对。");
    }
  }

  private String currentBoot() {
    try {
      int boot =
          Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
      if (boot >= 0) return "boot-" + boot;
    } catch (SecurityException ignored) {
    }
    // Without a readable boot count, only claim pauses made by this process lifetime.
    return PROCESS_SESSION;
  }

  private boolean claim(Hold hold, String boot, String driver) {
    boolean saved =
        prefs
            .edit()
            .putBoolean("cruiseOwned", hold == Hold.OWNED)
            .putBoolean("cruiseUncertain", hold == Hold.UNCERTAIN)
            .putString("cruiseBoot", boot)
            .putString("cruiseBattery", driver)
            .commit();
    if (!saved) problem("归根无法保存停充归属；请检查驱动当前状态。");
    return saved;
  }

  void close() {
    closed = true;
    main.removeCallbacks(finishStop);
    worker.removeCallbacks(poll);
    worker.post(
        () -> {
          releaseConnection();
          thread.quitSafely();
        });
  }
}
