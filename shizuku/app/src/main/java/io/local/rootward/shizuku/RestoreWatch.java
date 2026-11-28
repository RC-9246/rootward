package io.local.rootward.shizuku;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Resume a previously enabled recorder after the user's data becomes available. */
public final class RestoreWatch extends BroadcastReceiver {
  static final int RECOVERY_NOTICE = 35;

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
    if (!ObserverApp.prefs(context).getBoolean("watch", false)) return;
    try {
      WatchNotices.start(context, new Intent(context, WatchService.class));
    } catch (IllegalStateException | SecurityException restricted) {
      // Keep the user's choice. Opening the activity can retry from the foreground.
      Log.w("Rootward", "Background monitoring restart was restricted", restricted);
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (!manager.areNotificationsEnabled()) return;
      WatchNotices.ensureChannels(manager);
      PendingIntent open =
          PendingIntent.getActivity(
              context,
              RECOVERY_NOTICE,
              new Intent(context, MainActivity.class),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      manager.notify(
          RECOVERY_NOTICE,
          WatchNotices.builder(context, WatchService.ALERT)
              .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
              .setContentTitle("电池记录等待恢复")
              .setContentText("系统限制了后台启动，点此打开归根继续记录。")
              .setContentIntent(open)
              .setAutoCancel(true)
              .build());
    }
  }
}
