package io.local.rootward.shizuku;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All network and ownership changes run in order, including configuration and explicit recovery.
 */
final class SmartOutlet {
  interface Done {
    void result(String text);
  }

  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static volatile Sample latest;
  private static long lastAttempt;
  private static boolean queued;

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("home_assistant", 0);
  }

  private static final class Sample {
    final String request;
    final long until, at = SystemClock.elapsedRealtime();

    Sample(String request, long until) {
      this.request = request;
      this.until = until;
    }

    String current(SharedPreferences policy) {
      return SystemClock.elapsedRealtime() - at > 150000
              || (until > 0
                  && (System.currentTimeMillis() >= until
                      || BoostPlan.start(policy) != until - BoostWindow.HOUR
                      || policy.getBoolean("thermal", false)
                      || policy.getInt("phase", 0) != ChargePhase.WAIT))
          ? "unknown"
          : request;
    }
  }

  static synchronized void observe(Context c, String request, long until) {
    if (!prefs(c).getBoolean("active", false)) return;
    Sample previous = latest;
    latest = new Sample(request, until);
    if (previous != null
        && previous.request.equals(request)
        && previous.until == until
        && latest.at - lastAttempt < 45000) return;
    enqueue(c.getApplicationContext());
  }

  private static synchronized void enqueue(Context c) {
    if (queued) return;
    queued = true;
    IO.execute(
        () -> {
          synchronized (SmartOutlet.class) {
            queued = false;
            lastAttempt = SystemClock.elapsedRealtime();
          }
          sync(c);
        });
  }

  private static String desired(Context c) {
    if (!prefs(c).getBoolean("active", false) || !ObserverApp.prefs(c).getBoolean("watch", false))
      return "stop";
    Sample s = latest;
    return s == null ? "unknown" : s.current(ObserverApp.prefs(c));
  }

  static synchronized void stop(Context c, Runnable finished) {
    SharedPreferences p = prefs(c);
    latest = null;
    if (p.getBoolean("active", false) || !p.getString("owner", "").isEmpty()) {
      p.edit().putBoolean("active", false).apply();
      Context app = c.getApplicationContext();
      IO.execute(
          () -> {
            sync(app);
            MAIN.post(finished);
          });
    } else finished.run();
  }

  private static HaSwitch api(SharedPreferences p) throws Exception {
    return new HaSwitch(
        p.getString("origin", ""),
        p.getString("entity", ""),
        OutletSecret.open(p.getString("secret", "")));
  }

  private static String status(SharedPreferences p, String message) {
    p.edit().putString("status", message).putLong("checked", System.currentTimeMillis()).apply();
    return message;
  }

  private static void mark(SharedPreferences p, String value) throws IOException {
    if (!p.edit().putString("owner", value).commit()) throw new IOException("本机状态未保存");
  }

  private static String failure(Exception e) {
    String text = e.getMessage();
    if (e instanceof IOException && text != null && text.startsWith("Home Assistant 返回 HTTP "))
      return text;
    return "未确认插座状态，请检查网络、HTTPS 证书、令牌或实体";
  }

  private static String outcome(Context c, String message, boolean unresolved) {
    SharedPreferences p = prefs(c);
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    boolean attention = unresolved && !p.getString("owner", "").isEmpty();
    if (attention
        && !p.getBoolean("needsAttention", false)
        && nm.areNotificationsEnabled()
        && nm.getNotificationChannel(WatchService.ALERT) != null) {
      PendingIntent open =
          PendingIntent.getActivity(
              c,
              37,
              new Intent(c, MainActivity.class).setAction("io.local.rootward.shizuku.OPEN_OUTLET"),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      nm.notify(
          37,
          new Notification.Builder(c, WatchService.ALERT)
              .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
              .setContentTitle("请核对充电插座")
              .setContentText(message)
              .setStyle(new Notification.BigTextStyle().bigText(message))
              .setContentIntent(open)
              .setAutoCancel(true)
              .build());
      p.edit().putBoolean("needsAttention", true).apply();
    } else if (!attention && p.getBoolean("needsAttention", false)) {
      nm.cancel(37);
      p.edit().remove("needsAttention").apply();
    }
    return status(p, message);
  }

  private static String sync(Context c) {
    SharedPreferences p = prefs(c);
    String own = p.getString("owner", "");
    if (desired(c).equals("stop") && own.isEmpty()) return outcome(c, "插座联动已关闭。", false);
    try {
      HaSwitch api = api(p);
      HaSwitch.State s = api.read();
      String request = desired(c);
      OutletPolicy.Step step = OutletPolicy.decide(request, s.mode, s.changed, own);
      if (step == OutletPolicy.Step.CLEAR) {
        mark(p, "");
        step = OutletPolicy.decide(request, s.mode, s.changed, "");
      }
      if (!s.known()) return outcome(c, "插座离线或状态未知，请核对供电。", true);
      if (step == OutletPolicy.Step.UNCERTAIN)
        return outcome(c, "上一次断电回执不完整。请核对插座，需要时手动恢复供电。", true);
      if (step == OutletPolicy.Step.NONE)
        return outcome(
            c,
            "插座报告"
                + (s.mode.equals("on") ? "通电。" : "断电。")
                + (s.mode.equals("off") && p.getString("owner", "").isEmpty()
                    ? "原本已断电，归根不会自动开启。"
                    : ""),
            false);
      boolean on = step == OutletPolicy.Step.ON;
      if (!on) mark(p, OutletPolicy.PENDING);
      if (!desired(c).equals(request)) {
        if (!on) mark(p, "");
        enqueue(c);
        return "策略已更新";
      }
      api.set(on);
      HaSwitch.State actual = api.read();
      if (!actual.mode.equals(on ? "on" : "off")
          || (!on && (actual.changed.isEmpty() || actual.changed.equals(s.changed))))
        return outcome(c, "指令已发送，插座回读尚未确认。", true);
      mark(p, on ? "" : actual.changed);
      return outcome(c, on ? "已回读：插座恢复通电。" : "已回读：插座停止供电。", false);
    } catch (Exception e) {
      return outcome(c, failure(e) + "。上一次动作可能仍然有效。", true);
    }
  }

  private static void reply(Done done, String text) {
    MAIN.post(() -> done.result(text));
  }

  static void setup(Context c, String origin, String entity, String token, Done done) {
    Context app = c.getApplicationContext();
    IO.execute(
        () -> {
          SharedPreferences p = prefs(app);
          try {
            if (p.getBoolean("active", false) || !p.getString("owner", "").isEmpty()) {
              reply(done, "请先停用联动并核对上次断电状态，再更换配置。");
              return;
            }
            HaSwitch api = new HaSwitch(origin, entity, token);
            HaSwitch.State s = api.read();
            if (!s.known()) {
              reply(done, "实体未回报可用的开关状态。");
              return;
            }
            if (!p.edit()
                .putString("secret", OutletSecret.seal(token))
                .putString("origin", api.origin)
                .putString("entity", api.entity)
                .commit()) throw new IOException();
            reply(done, status(p, "连接验证成功，插座报告" + (s.mode.equals("on") ? "通电。" : "断电。")));
          } catch (IllegalArgumentException e) {
            reply(done, e.getMessage());
          } catch (Exception e) {
            reply(done, failure(e));
          }
        });
  }

  static void check(Context c, boolean recover, Done done) {
    Context app = c.getApplicationContext();
    IO.execute(
        () -> {
          SharedPreferences p = prefs(app);
          try {
            HaSwitch api = api(p);
            HaSwitch.State s = api.read();
            if (recover && p.getBoolean("active", false)) {
              reply(done, "请先停用联动。");
              return;
            }
            if (!s.known()) {
              reply(done, outcome(app, "插座状态未知，请稍后再检查。", true));
              return;
            }
            if (recover && s.mode.equals("off")) {
              api.set(true);
              s = api.read();
            }
            if (s.known() && s.mode.equals("on") && !p.getString("owner", "").isEmpty()) mark(p, "");
            reply(
                done,
                outcome(
                    app,
                    s.mode.equals("on") ? "插座报告通电。" : s.mode.equals("off") ? "插座报告断电。" : "插座状态未知。",
                    !s.known() || OutletPolicy.PENDING.equals(p.getString("owner", ""))));
          } catch (Exception e) {
            reply(done, outcome(app, failure(e), true));
          }
        });
  }

  static void enable(Context c, boolean active) {
    prefs(c).edit().putBoolean("active", active).apply();
    latest = null;
    if (!active) enqueue(c.getApplicationContext());
  }

  static void forget(Context c, Done done) {
    Context app = c.getApplicationContext();
    IO.execute(
        () -> {
          SharedPreferences p = prefs(app);
          if (p.getBoolean("active", false)) {
            reply(done, "请先关闭联动。");
            return;
          }
          if (!p.edit().clear().commit()) {
            reply(done, "移除配置失败，请重试。");
            return;
          }
          app.getSystemService(NotificationManager.class).cancel(37);
          reply(done, "本机插座配置与加密令牌已移除。");
        });
  }

  private SmartOutlet() {}
}
