package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/** Optional, user-initiated permission for Home Assistant hosts on the local network. */
final class OutletNetwork {
  private static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
  private static final String ASKED = "outletLanPermissionAsked";

  static boolean missing(Context c) {
    return Build.VERSION.SDK_INT >= 37
        && c.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED;
  }

  static void bindPermissionButton(Activity a, AlertDialog dialog, Runnable onGranted) {
    Button button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
    if (button == null || button.getVisibility() != View.VISIBLE) return;
    button.setOnClickListener(v -> request(a));
    button.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
      if (focused && button.getVisibility() == View.VISIBLE && !missing(a)) {
        button.setVisibility(View.GONE);
        onGranted.run();
      }
    });
  }

  private static void request(Activity a) {
    if (!missing(a)) {
      Toast.makeText(a, "已允许访问局域网", Toast.LENGTH_SHORT).show();
      return;
    }
    if (ObserverApp.prefs(a).getBoolean(ASKED, false)
        && !a.shouldShowRequestPermissionRationale(PERMISSION)) {
      Intent settings =
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + a.getPackageName()));
      try {
        a.startActivity(settings);
        return;
      } catch (android.content.ActivityNotFoundException ignored) {
        // Some constrained devices omit the application details panel.
      }
    }
    ObserverApp.prefs(a).edit().putBoolean(ASKED, true).apply();
    a.requestPermissions(new String[] {PERMISSION}, 48);
  }

  private OutletNetwork() {}
}
