package io.local.rootward.shizuku;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import rikka.shizuku.ShizukuProvider;

public final class ObserverApp extends Application {
  @Override
  protected void attachBaseContext(Context context) {
    super.attachBaseContext(context);
    ShizukuProvider.disableAutomaticSuiInitialization();
  }

  static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("watch", MODE_PRIVATE);
  }
}
