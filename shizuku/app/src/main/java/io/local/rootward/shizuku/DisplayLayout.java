package io.local.rootward.shizuku;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;

final class DisplayLayout {
  static void protectContent(Activity activity, View page) {
    if (Build.VERSION.SDK_INT < 30) return;
    Window window = activity.getWindow();
    window.setDecorFitsSystemWindows(false);
    window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
    window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    window.setNavigationBarContrastEnforced(false);
    WindowInsetsController bars = window.getInsetsController();
    int icons =
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
    if (bars != null)
      bars.setSystemBarsAppearance(
          activity.getResources().getBoolean(R.bool.light_bars) ? icons : 0, icons);
    page.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          Insets safe =
              insets.getInsets(
                  WindowInsets.Type.systemBars()
                      | WindowInsets.Type.displayCutout()
                      | WindowInsets.Type.ime());
          v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
  }

  static void labelInput(TextView label, EditText input) {
    input.setId(View.generateViewId());
    label.setLabelFor(input.getId());
    int minimum = Math.round(48 * input.getResources().getDisplayMetrics().density);
    input.setMinHeight(minimum);
  }

  static ScrollView scrollForm(View form) {
    ScrollView scroll = new ScrollView(form.getContext());
    scroll.setFocusable(false);
    scroll.addView(form);
    return scroll;
  }

  private DisplayLayout() {}
}
