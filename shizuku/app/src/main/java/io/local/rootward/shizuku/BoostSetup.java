package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class BoostSetup {
  static boolean eligible(SharedPreferences p, Reading r) {
    return p.getBoolean("watch", false) && p.getInt("phase", ChargePhase.FILL) == ChargePhase.WAIT
        && r != null && r.level > p.getInt("low", 50) && r.level < p.getInt("high", 75);
  }

  static void open(Activity a, SharedPreferences p, Runnable refresh) {
    if (BoostPlan.start(p) != 0) {
      BoostPlan.clear(p.edit()).apply();
      a.getSystemService(NotificationManager.class).cancel(BoostPlan.NOTICE);
      refresh.run();
      return;
    }
    if (!eligible(p, Reading.now(a))) return;
    LocalDate today = LocalDate.now();
    new AlertDialog.Builder(a).setTitle("预约哪一天补电？")
        .setItems(new String[]{"今天 · " + today, "明天 · " + today.plusDays(1)},
            (d, index) -> pick(a, p, today.plusDays(index), refresh))
        .setNegativeButton("取消", null).show();
  }

  private static void pick(Activity a, SharedPreferences p, LocalDate day, Runnable refresh) {
    LocalTime initial = LocalTime.now().plusMinutes(5);
    AlertDialog.Builder builder = new AlertDialog.Builder(a);
    TimePicker picker = new TimePicker(builder.getContext());
    picker.setIs24HourView(true);
    picker.setHour(initial.getHour());
    picker.setMinute(initial.getMinute());
    LinearLayout content = new LinearLayout(builder.getContext());
    content.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(12 * a.getResources().getDisplayMetrics().density);
    content.setPadding(pad, pad, pad, pad);
    TextView title = new TextView(builder.getContext());
    title.setText("选择补电窗口的开始时间");
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    content.addView(title);
    content.addView(picker);
    Button next = new Button(builder.getContext());
    next.setText("继续");
    content.addView(next);
    Button cancel = new Button(builder.getContext());
    cancel.setText("取消");
    content.addView(cancel);
    AlertDialog dialog = builder.setView(DisplayLayout.scrollForm(content)).show();
    cancel.setOnClickListener(v -> dialog.dismiss());
    next.setOnClickListener(v -> {
      picker.clearFocus();
      if (Build.VERSION.SDK_INT >= 26 && !picker.validateInput()) return;
      LocalDateTime time = day.atTime(picker.getHour(), picker.getMinute());
      ZoneId zone = ZoneId.systemDefault();
      Instant now = Instant.now();
      for (ZoneOffset offset : zone.getRules().getValidOffsets(time)) {
        long start = time.toInstant(offset).toEpochMilli();
        if (start <= now.toEpochMilli()) continue;
        int lo = p.getInt("low", 50), hi = p.getInt("high", 75), heat = p.getInt("heat", 40);
        dialog.dismiss();
        new AlertDialog.Builder(a).setTitle("确认本轮预约")
            .setMessage(BoostPlan.window(start) + "（" + zone + "）。温度允许时，窗口内可提前补到原上限 "
                + hi + "%。到上限、自然回落到下限或窗口结束即恢复常规策略。"
                + "仅用提醒时请按通知接电或拔线，系统休眠可能延迟通知与硬件操作。")
            .setNegativeButton("取消", null).setPositiveButton("保存预约", (d, w) -> {
              if (!eligible(p, Reading.now(a)) || BoostPlan.start(p) != 0
                  || lo != p.getInt("low", 50) || hi != p.getInt("high", 75)
                  || heat != p.getInt("heat", 40) || start <= System.currentTimeMillis()) {
                Toast.makeText(a, "当前阶段或时间已改变，请重新选择。", Toast.LENGTH_LONG).show();
                refresh.run();
                return;
              }
              BoostPlan.save(p, start);
              refresh.run();
            }).show();
        return;
      }
      Toast.makeText(a, "请选择当天尚未经过、在本地时区有效的时间。", Toast.LENGTH_LONG).show();
    });
  }
}
