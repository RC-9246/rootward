package io.local.rootward.shizuku;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public final class ObserverApp extends Application {
  @Override
  protected void attachBaseContext(Context context) {
    super.attachBaseContext(context);
    ShizukuProvider.disableAutomaticSuiInitialization();
  }

  static boolean shellPermission() {
    // The client helper caches granted permission. Ask the same server API directly so a
    // long-lived diagnostic Binder cannot outlive permission revocation.
    android.os.IBinder gateway = Shizuku.getBinder();
    if (gateway == null) return false;
    try {
      return moe.shizuku.server.IShizukuService.Stub.asInterface(gateway).checkSelfPermission();
    } catch (android.os.RemoteException | RuntimeException e) {
      return false;
    }
  }

  static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("watch", MODE_PRIVATE);
  }
}
