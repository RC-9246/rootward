package io.local.rootward.shizuku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Maps recorder notifications and service starts onto the APIs of the installed OS. */
final class WatchNotices {
  static void ensureChannels(NotificationManager manager) {
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(new NotificationChannel(
          WatchService.STATUS, "持续记录", NotificationManager.IMPORTANCE_LOW));
      manager.createNotificationChannel(new NotificationChannel(
          WatchService.ALERT, "电量与温度提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }
  }

  static Notification.Builder builder(Context c, String channel) {
    if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
    boolean ongoing = WatchService.STATUS.equals(channel);
    Notification.Builder b = new Notification.Builder(c).setPriority(
        ongoing ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
    if (!ongoing) b.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
    return b;
  }

  static void start(Context c, Intent intent) {
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent);
    else c.startService(intent);
  }

  private WatchNotices() {}
}
