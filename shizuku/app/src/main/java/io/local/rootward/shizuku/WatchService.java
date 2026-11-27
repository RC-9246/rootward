package io.local.rootward.shizuku;

import android.app.*;
import android.content.*;
import android.os.*;

public final class WatchService extends Service {
  static final String STATUS = "watch",
      ALERT = "limit",
      UPDATE = "io.local.rootward.shizuku.UPDATE";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Archive archive;
  private CruiseDriver cruise;
  private long segment;
  private int lastLevel = -2, lastPlug = -2, lastStatus = -2, lastTemp = Integer.MIN_VALUE;
  private long lastSaved;
  private boolean registered;
  static boolean running;
  private final Runnable timer =
      new Runnable() {
        public void run() {
          tick();
          handler.postDelayed(this, 60000);
        }
      };
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          tick();
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    running = true;
    archive = new Archive(this);
    cruise = new CruiseDriver(this);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(STATUS, "持续记录", NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(
        new NotificationChannel(ALERT, "电量与温度提醒", NotificationManager.IMPORTANCE_DEFAULT));
  }

  private Notification notice(String channel, String title, String detail, boolean ongoing) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b =
        new Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing);
    if (ongoing) {
      PendingIntent stop =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, WatchService.class).setAction("stop"),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止记录", stop);
    }
    return b.build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    SharedPreferences p = ObserverApp.prefs(this);
    if ((i != null && "stop".equals(i.getAction())) || !p.getBoolean("watch", false)) {
      p.edit().putBoolean("watch", false).putBoolean("cruise", false).apply();
      startForeground(31, notice(STATUS, "归根正在停止记录", "检查本次巡航留下的驱动状态", true));
      cruise.stop(() -> stopSelf(id));
      return START_NOT_STICKY;
    }
    startForeground(31, notice(STATUS, "归根正在记录", "电量、温度和充放电状态", true));
    if (!registered) {
      segment = System.currentTimeMillis();
      registered = true;
      registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      handler.post(timer);
    } else tick();
    return START_STICKY;
  }

  private void tick() {
    Reading r = Reading.now(this);
    if (r == null) {
      cruise.sample();
      return;
    }
    SharedPreferences p = ObserverApp.prefs(this);
    if (!p.getBoolean("watch", false)) return;
    int lo = p.getInt("low", 50), hi = p.getInt("high", 75), hot = p.getInt("heat", 40);
    int old = p.getInt("phase", 0), phase = ChargePhase.sample(old, r.level, lo, hi);
    boolean thermal = p.getBoolean("thermal", false);
    boolean hardware = p.getBoolean("cruise", false);
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (phase != old) {
      p.edit().putInt("phase", phase).apply();
      String msg;
      if (phase == ChargePhase.WAIT) msg = hardware ? "达到巡航上限，正在核对驱动停充。" : "达到提醒上限，可拔线或手动暂停充电。";
      else msg = hardware ? "达到恢复下限，正在核对温度及驱动状态。" : "达到恢复下限，需要时可接电或手动恢复。";
      nm.notify(32, notice(ALERT, r.level + "% · 电量提醒", msg, false));
    }
    if (!thermal && r.temp != Integer.MIN_VALUE && r.temp >= hot * 10) {
      p.edit().putBoolean("thermal", true).apply();
      nm.notify(
          33,
          notice(
              ALERT,
              "电池温度 " + r.temperature(),
              hardware ? "达到巡航温度上限，正在核对驱动停充。" : "可减少负载或手动暂停充电。",
              false));
    } else if (thermal && r.temp != Integer.MIN_VALUE && r.temp <= (hot - 2) * 10)
      p.edit().putBoolean("thermal", false).apply();
    cruise.sample();
    if (lastSaved == 0
        || r.time - lastSaved >= 55000
        || r.time < lastSaved
        || r.level != lastLevel
        || r.plug != lastPlug
        || r.status != lastStatus
        || r.temp != Integer.MIN_VALUE && Math.abs((long) r.temp - lastTemp) >= 10) {
      archive.record(r, segment);
      lastSaved = r.time;
      lastLevel = r.level;
      lastPlug = r.plug;
      lastStatus = r.status;
      lastTemp = r.temp;
      nm.notify(
          31,
          notice(
              STATUS,
              "归根 · " + (r.level < 0 ? "—" : r.level + "%") + "  " + r.temperature(),
              r.source() + " · " + r.state(),
              true));
      sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }
  }

  @Override
  public void onDestroy() {
    running = false;
    if (registered) unregisterReceiver(receiver);
    handler.removeCallbacks(timer);
    cruise.close();
    archive.close();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
