package io.local.rootward.shizuku;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int GREEN = 0xff135d49;
  private int ink, surface;
  private SharedPreferences prefs;
  private TextView level, state, metrics, limits, summary, phase, shizStatus, hardwareStatus;
  private Button watch, shizAction, pauseDriver, resumeDriver;
  private Switch manual, cruise;
  private Button appointment;
  private TextView appointmentDetail;
  private Graph graph;
  private Archive archive;
  private long lastChartRefresh = -60000;
  private int chartUpper = -1, chartHeat = -1;
  private ShellLink shell;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          render();
          h.postDelayed(this, 10000);
        }
      };

  private int px(int dp) {
    return (int) (getResources().getDisplayMetrics().density * dp + .5);
  }

  private LinearLayout box() {
    LinearLayout p = new LinearLayout(this);
    p.setOrientation(1);
    return p;
  }

  private TextView text(LinearLayout p, String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, px(4), 0, px(4));
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    p.addView(t);
    return t;
  }

  private LinearLayout card(LinearLayout root, String title, int color) {
    LinearLayout p = box();
    p.setPadding(px(16), px(12), px(16), px(12));
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(px(13));
    p.setBackground(d);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = px(12);
    root.addView(p, lp);
    text(p, title, 18, color == GREEN ? Color.WHITE : ink, true);
    return p;
  }

  private Button btn(LinearLayout p, String s, View.OnClickListener l) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setSingleLine(false);
    b.setMaxLines(Integer.MAX_VALUE);
    b.setText(s);
    b.setOnClickListener(l);
    p.addView(b);
    return b;
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = ObserverApp.prefs(this);
    archive = new Archive(this);
    ScrollView scroll = new ScrollView(this);
    ink = getColor(R.color.body_ink);
    surface = getColor(R.color.surface);
    scroll.setId(R.id.observer_scroll);
    scroll.setBackgroundColor(getColor(R.color.page));
    LinearLayout root = box();
    root.setPadding(px(16), px(12), px(16), px(8));
    scroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int width = right - left - scroll.getPaddingLeft() - scroll.getPaddingRight();
      int margin = Math.max(px(16), (width - px(680)) / 2);
      if (root.getPaddingLeft() != margin) root.setPadding(margin, px(12), margin, px(8));
    });
    scroll.addView(root);
    setContentView(scroll);
    DisplayLayout.protectContent(this, scroll);
    text(root, "归根 · Shizuku", 27, ink, true);
    LinearLayout hero = card(root, "电池概览", GREEN);
    level = text(hero, "—", 39, Color.WHITE, true);
    state = text(hero, "", 17, Color.WHITE, false);
    metrics = text(hero, "", 15, Color.WHITE, false);
    btn(hero, "供电与散热诊断", v -> PowerCareDialog.open(this));
    LinearLayout control = card(root, "充电区间", surface);
    limits = text(control, "", 16, ink, true);
    phase = text(control, "", 15, ink, false);
    manual = new Switch(this);
    manual.setText("再次提醒我到达上限");
    manual.setMinHeight(px(48));
    control.addView(manual);
    manual.setOnClickListener(v -> toggleManual());
    appointment = btn(control, "预约单次补电", v -> BoostSetup.open(this, prefs, () -> {
      refreshWatch();
      render();
    }));
    appointmentDetail = text(control, "", 14, ink, false);
    watch = btn(control, "开始记录与提醒", v -> toggleWatch());
    btn(control, "设置电量与温度阈值", v -> configure());
    btn(control, "保存与切换养护方案", v -> CareProfiles.show(this, prefs, this::applyLimits));
    btn(control, "打开系统电池设置", v -> PowerCareDialog.settings(this, false));
    btn(control, "打开系统省电设置", v -> PowerCareDialog.settings(this, true));
    LinearLayout hw = card(root, "兼容设备充电开关", surface);
    hardwareStatus = text(hw, "", 15, ink, false);
    cruise = new Switch(this);
    cruise.setText("按阈值自动巡航");
    cruise.setMinHeight(px(48));
    hw.addView(cruise);
    cruise.setOnClickListener(v -> toggleCruise());
    pauseDriver = btn(hw, "手动暂停充电", v -> shell.setPaused(true));
    resumeDriver = btn(hw, "恢复系统自动充电", v -> shell.setPaused(false));
    btn(hw, "查看系统原生限充", v -> NativeLimitsDialog.open(this, shell));
    LinearLayout sh = card(root, "Shizuku 驱动诊断", surface);
    shizStatus = text(sh, "", 15, ink, false);
    shizStatus.setTextIsSelectable(true);
    shizAction = btn(sh, "检查连接", v -> shell.act());
    btn(
        sh,
        "查看各应用耗电估算",
        v -> {
          AlertDialog dialog =
              new AlertDialog.Builder(this)
                  .setTitle("各应用耗电估算")
                  .setMessage("正在读取系统统计…")
                  .setPositiveButton("关闭", null)
                  .show();
          shell.appUsage(
              result -> {
                if (!isFinishing() && !isDestroyed() && dialog.isShowing())
                  dialog.setMessage(result);
              });
        });
    btn(
        sh,
        "打开 Shizuku 管理器",
        v -> {
          Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
          if (i == null) msg("未找到 Shizuku 管理器");
          else startActivity(i);
        });
    LinearLayout history = card(root, "本机记录", surface);
    summary = text(history, "", 15, ink, false);
    btn(history, "查看养护历史与接电记录", v -> historyReport());
    btn(history, "用量与容量分析", v -> batteryAnalysis());
    text(history, "最近 1 小时电量 · 0–100%", 13, ink, false);
    graph = new Graph();
    history.addView(graph, new LinearLayout.LayoutParams(-1, px(75)));
    btn(
        history,
        "导出最近 30 天 CSV",
        v -> {
          Intent i =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType("text/csv")
                  .putExtra(Intent.EXTRA_TITLE, "Rootward-battery.csv");
          try {
            startActivityForResult(i, 9);
          } catch (ActivityNotFoundException e) {
            msg("没有可用的文件选择器");
          }
        });
    btn(history, "连接本机自动化应用", v -> automationSettings());
    btn(history, "Home Assistant 充电插座", v -> OutletDialog.show(this, this::refreshWatch));
    shell =
        new ShellLink(
            this,
            (detail, action, enabled) -> {
              shizStatus.setText(detail);
              shizAction.setText(action);
              shizAction.setEnabled(enabled);
              renderHardware();
            });
    shell.open();
    render();
    if (b == null && "io.local.rootward.shizuku.OPEN_OUTLET".equals(getIntent().getAction()))
      OutletDialog.show(this, this::refreshWatch);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (prefs.getBoolean("watch", false) && !WatchService.running) startWatch();
    IntentFilter changes = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    changes.addAction(WatchService.UPDATE);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(batteryChanged, changes, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(batteryChanged, changes);
    h.post(refresh);
    if (shell != null) shell.refresh();
    prefs.registerOnSharedPreferenceChangeListener(driverUpdates);
  }

  @Override
  protected void onPause() {
    prefs.unregisterOnSharedPreferenceChangeListener(driverUpdates);
    unregisterReceiver(batteryChanged);
    h.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    shell.close();
    files.execute(archive::close);
    files.shutdown();
    super.onDestroy();
  }

  private final SharedPreferences.OnSharedPreferenceChangeListener driverUpdates =
      (store, key) -> {
        if ("cruiseStatus".equals(key)
            || "cruiseOwned".equals(key)
            || "cruiseUncertain".equals(key)
            || "cruise".equals(key)
            || "watch".equals(key)
            || BoostPlan.START.equals(key) || "phase".equals(key)) render();
      };

  private final BroadcastReceiver batteryChanged =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          render();
        }
      };

  private void render() {
    Reading r = Reading.now(this);
    int lo = prefs.getInt("low", 50), hi = prefs.getInt("high", 75), hot = prefs.getInt("heat", 40);
    if (r != null) {
      level.setText(r.level < 0 ? "—" : r.level + "%");
      state.setText(r.source() + " · " + r.state() + " · " + r.temperature());
      String power =
          r.mv > 0 && r.current != null
              ? String.format(Locale.CHINA, "%.2f W", r.mv * r.current.doubleValue() / 1e9)
              : "—";
      String remaining = r.chargeUah > 0 ? Math.round(r.chargeUah / 1000d) + " mAh" : "—";
      metrics.setText(
          "电池端  "
              + (r.mv > 0 ? r.mv + " mV" : "—")
              + "  ·  "
              + (r.current != null ? Math.round(r.current / 1000d) + " mA" : "—")
              + "  ·  "
              + power
              + "\n预计至满充  "
              + (r.fullMs > 0
                  ? "约 " + Math.max(1, Math.round(r.fullMs / 60000d)) + " 分钟"
                  : "暂无系统估计")
              + "\n剩余电荷  "
              + remaining
              + "  ·  健康状态  "
              + r.healthLabel()
              + (r.cycles >= 0 ? "  ·  循环 " + r.cycles + " 次" : ""));
    }
    boolean on = prefs.getBoolean("watch", false);
    int p = prefs.getInt("phase", 0);
    watch.setText(on ? "停止记录与提醒" : "开始记录与提醒");
    limits.setText(lo + "% 恢复提醒  ·  " + hi + "% 上限提醒  ·  " + hot + "°C 高温提醒");
    boolean alerts = getSystemService(NotificationManager.class).areNotificationsEnabled();
    boolean hardware = prefs.getBoolean("cruise", false);
    boolean outlet = SmartOutlet.prefs(this).getBoolean("active", false);
    boolean sharing = !prefs.getString(AutomationSignal.RECIPIENT, "").isEmpty();
    manual.setText(hardware || outlet ? "手动继续充电一次"
        : sharing ? "手动放行本轮充电建议" : "再次提醒我到达上限");
    String controlHint;
    if (hardware) controlHint = "硬件状态见下方巡航读数，提前补电仍受温控约束。";
    else if (outlet) controlHint = "本轮建议用于智能插座联动，供电状态以插座回读为准。";
    else if (sharing) controlHint = "归根将本轮建议共享给指定应用，实际动作由接收方确认。";
    else controlHint = "开关可重新安排上限提醒，实际充电由系统决定。";
    BoostWindow.Stage planStage = BoostPlan.stage(prefs, System.currentTimeMillis(), p, r == null ? -1 : r.level);
    String waiting = planStage == BoostWindow.Stage.OPEN
        ? "预约窗口内按原上限 " + hi + "% 和温控处理。"
        : "已到上限，记录回落至 " + lo + "%。";
    phase.setText(
        (on
                ? (p == ChargePhase.WAIT
                    ? waiting
                    : p == ChargePhase.MANUAL
                        ? "本次已继续，达到 " + hi + "% 时再次提醒。"
                        : "正在等候到达 " + hi + "%。")
                : "记录已暂停。")
            + " 插拔不重置阶段。" + controlHint
            + (on && !alerts ? " 系统通知已关闭。" : ""));
    manual.setChecked(p == ChargePhase.MANUAL);
    manual.setEnabled(
        on
            && r != null
            && r.level > lo
            && r.level < hi
            && (p == ChargePhase.WAIT || p == ChargePhase.MANUAL));
    long at = BoostPlan.start(prefs);
    appointment.setText(at == 0 ? "预约单次补电" : "取消本轮预约");
    appointment.setEnabled(at != 0 || BoostSetup.eligible(prefs, r));
    appointmentDetail.setText(at == 0 ? "等待下限时可预约今天或明天提前补电，仍遵守原上限与温控。"
        : "预约窗口　" + BoostPlan.window(at) + "。手动提前一轮会替换预约。");
    renderHardware();
    long now = SystemClock.elapsedRealtime();
    if (now - lastChartRefresh >= 60000 || hi != chartUpper || hot != chartHeat) {
      chartUpper = hi;
      chartHeat = hot;
      lastChartRefresh = now;
      files.execute(() -> {
        UsageWindow report = archive.window(1, hi, hot);
        ArrayList<PointF> points = archive.recentLevels();
        runOnUiThread(() -> {
          if (isDestroyed() || isFinishing() || chartUpper != hi || chartHeat != hot) return;
          summary.setText("近 24 小时已记录 " + UsageWindow.duration(report.covered)
              + "，高电量与高温重叠 " + UsageWindow.duration(report.overlap));
          graph.data = points;
          graph.invalidate();
        });
      });
    }
  }

  private void renderHardware() {
    if (shell == null || cruise == null) return;
    boolean active = prefs.getBoolean("cruise", false);
    boolean watching = prefs.getBoolean("watch", false);
    int mode = shell.mode();
    boolean usable = shell.canWrite();
    cruise.setChecked(active);
    cruise.setEnabled(active || (usable && watching));
    pauseDriver.setEnabled(!active && usable && mode == ChargeBehaviour.AUTO);
    resumeDriver.setEnabled(!active && usable && mode == ChargeBehaviour.INHIBITED);
    String detail;
    if (active) {
      detail = prefs.getString("cruiseStatus", "巡航已开启，正在核对驱动。");
    } else {
      switch (mode) {
        case ChargeBehaviour.AUTO:
          detail = "上次驱动读数为系统自动充电模式。";
          break;
        case ChargeBehaviour.INHIBITED:
          detail = "上次驱动读数为停充模式。手动暂停后，需手动恢复系统自动充电。";
          break;
        case ChargeBehaviour.DENIED:
          detail = "ADB shell 无权读写本机标准停充接口。可使用电量提醒和系统电池设置。";
          break;
        case ChargeBehaviour.EXTERNAL:
          detail = "驱动处于其他充电模式，已保留当前策略。";
          break;
        case ChargeBehaviour.UNCONFIRMED:
          detail = "驱动状态无法确认，请刷新诊断。";
          break;
        default:
          detail = "点下方 Shizuku 诊断检查本机标准停充接口。未提供可写接口时，可使用提醒和系统电池设置。";
          break;
      }
      if ((mode == ChargeBehaviour.AUTO || mode == ChargeBehaviour.INHIBITED) && !watching)
        detail += " 开始记录后可启用自动巡航，当前没有上限提醒。";
      if (prefs.getBoolean("cruiseOwned", false) || prefs.getBoolean("cruiseUncertain", false))
        detail += " 上次巡航可能留有停充设置，请刷新驱动，必要时点「恢复系统自动充电」。";
    }
    hardwareStatus.setText(detail);
  }

  private void toggleCruise() {
    boolean active = prefs.getBoolean("cruise", false);
    cruise.setChecked(active);
    if (active) {
      prefs.edit().putBoolean("cruise", false).apply();
      refreshWatch();
      render();
      return;
    }
    if (!prefs.getBoolean("watch", false) || !shell.canWrite()) return;
    new AlertDialog.Builder(this)
        .setTitle("启用硬件自动巡航？")
        .setMessage(
            "归根会在达到电量或温度上限时请求驱动停充，回落至恢复下限且温度允许时恢复系统自动充电。插拔保留巡航阶段。系统温控及厂商上限仍然有效。若 Shizuku"
                + " 断开、归根被强制停止或卸载，停充可能仍然有效。请先关闭巡航并确认已恢复系统控制。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "启用巡航",
            (dialog, which) -> {
              if (!prefs.getBoolean("watch", false) || !shell.canWrite()) {
                msg("请先开启记录并刷新驱动读数");
                return;
              }
              prefs
                  .edit()
                  .putBoolean("cruise", true)
                  .putString("cruiseStatus", "正在核对驱动与当前阈值。")
                  .apply();
              refreshWatch();
              render();
            })
        .show();
  }

  private void refreshWatch() {
    if (!prefs.getBoolean("watch", false)) return;
    try {
      startForegroundService(new Intent(this, WatchService.class));
    } catch (RuntimeException e) {
      msg("系统未允许刷新记录服务");
    }
  }

  private void batteryAnalysis() {
    files.execute(
        () -> {
          BatteryAnalysis report = archive.analysis();
          Reading live = Reading.now(this);
          int lo = prefs.getInt("low", 50), hi = prefs.getInt("high", 75);
          String eta = "开启记录后可使用最新采样。";
          if (prefs.getBoolean("watch", false) && live != null) {
            int phase =
                ChargePhase.sample(prefs.getInt("phase", ChargePhase.FILL), live.level, lo, hi);
            boolean hot =
                HardwareCruise.hot(
                    prefs.getBoolean("thermal", false), live.temp, prefs.getInt("heat", 40));
            boolean planned =
                BoostWindow.permits(
                    BoostPlan.stage(prefs, live.time, phase, live.level),
                    live.level,
                    lo,
                    hi,
                    live.temp,
                    hot);
            boolean held =
                hot || live.temp == Integer.MIN_VALUE || (phase == ChargePhase.WAIT && !planned);
            eta = report.pace.describe(live.time, live.level, live.plug, live.status, lo, hi, held);
            if (planned && live.plug > 0) eta += " 预约窗口结束后将恢复原策略。";
          }
          String message = "按当前速率估算\n" + eta + "\n\n" + report.describe();
          runOnUiThread(
              () -> {
                if (isDestroyed() || isFinishing()) return;
                new AlertDialog.Builder(this)
                    .setTitle("用量与容量分析")
                    .setMessage(message)
                    .setPositiveButton("完成", null)
                    .setNeutralButton(
                        "计算依据",
                        (d, w) ->
                            new AlertDialog.Builder(this)
                                .setTitle("计算依据")
                                .setMessage(
                                    "到阈值时间取最近 30 分钟内同一电源、同一状态的连续变化。至少覆盖 5 分钟和 3"
                                        + " 个百分点，只外推观测跨度的三倍以内。实际充电可能因温度、使用和降速变化。\n\n"
                                        + "离电用量来自相邻电量样本的净变化。间隔超过 150 秒、同一间隔跳变超过 5"
                                        + " 个百分点或记录服务中断时跳过该段。设备可交互状态来自 Android；旧记录只计入全部离电。\n\n"
                                        + "容量来自 Android 剩余电荷计数（µAh）与电量百分比。每段接电最多取一段连续充电，限"
                                        + " 5%–95%，需跨越至少 20 个百分点、持续 5 分钟。估算用增加的 mAh"
                                        + " 除以电量占比；检查读数是否回退、是否随电量合理上升，并跳过疑似由整数电量直接换算的计数。\n\n"
                                        + "多段比较时，保留在各段中位数 ±15% 内的结果，至少 3 段且占候选的三分之二，才显示保留结果的中位数。")
                                .setPositiveButton("完成", null)
                                .show())
                    .show();
              });
        });
  }

  private void historyReport() {
    new AlertDialog.Builder(this).setTitle("选择记录范围")
        .setItems(new String[]{"近 24 小时", "近 7 天", "近 30 天"}, (dialog, index) -> {
          int days = new int[]{1, 7, 30}[index];
          int hi = prefs.getInt("high", 75), hot = prefs.getInt("heat", 40);
          files.execute(() -> {
            String message = archive.window(days, hi, hot).describe(days)
                + "\n\n按当前阈值统计。记录中断、电源类型变化或采样相隔超过 150 秒会分段，空白时段不计时。";
            runOnUiThread(() -> {
              if (!isDestroyed() && !isFinishing()) new AlertDialog.Builder(this)
                  .setTitle("养护历史").setMessage(message).setPositiveButton("完成", null)
                  .setNeutralButton("更改范围", (d, w) -> historyReport()).show();
            });
          });
        }).setNegativeButton("取消", null).show();
  }

  private void automationSettings() {
    LinearLayout panel = box();
    panel.setPadding(px(20), 0, px(20), 0);
    text(panel, "指定一个接收应用后，它可在记录期间读取当前电量、阈值和充电建议，用于联动自己的智能插座。最终开关状态由接收应用和插座确认。", 15, ink, false);
    EditText pkg = new EditText(this);
    pkg.setSingleLine(true);
    pkg.setHint("Android 包名，例如 net.dinglisch.android.taskerm");
    pkg.setText(prefs.getString(AutomationSignal.RECIPIENT, ""));
    panel.addView(pkg);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("本机自动化")
        .setView(DisplayLayout.scrollForm(panel)).setPositiveButton("保存", null)
        .setNegativeButton("取消", null).setNeutralButton("关闭共享", (d, w) -> {
          AutomationSignal.select(this, prefs, "");
          msg("已关闭共享");
        }).create();
    dialog.show();
    dialog.getButton(-1).setOnClickListener(v -> {
      String dest = pkg.getText().toString().trim();
      if (dest.length() > 255 || !dest.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
          || dest.equals(getPackageName())) {
        pkg.setError("请输入接收应用的完整包名");
        return;
      }
      AutomationSignal.select(this, prefs, dest);
      refreshWatch();
      dialog.dismiss();
      msg("已保存，开启记录后向所选应用共享当前状态");
    });
  }

  private void toggleManual() {
    Reading r = Reading.now(this);
    if (r == null) return;
    int lo = prefs.getInt("low", 50), hi = prefs.getInt("high", 75);
    int p = ChargePhase.toggleManual(prefs.getInt("phase", 0), r.level, lo, hi);
    BoostPlan.clear(prefs.edit()).putInt("phase", p).apply();
    getSystemService(NotificationManager.class).cancel(BoostPlan.NOTICE);
    refreshWatch();
    render();
  }

  private void toggleWatch() {
    if (prefs.getBoolean("watch", false)) {
      BoostPlan.clear(prefs.edit()).putBoolean("watch", false).putBoolean("cruise", false).apply();
      try {
        startForegroundService(new Intent(this, WatchService.class).setAction("stop"));
      } catch (RuntimeException e) {
        msg("系统未允许停止服务，请检查上次驱动读数");
      }
      render();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 5);
      return;
    }
    startWatch();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perm, int[] result) {
    super.onRequestPermissionsResult(code, perm, result);
    if (code == 5 && result.length > 0) {
      startWatch();
      if (result[0] != PackageManager.PERMISSION_GRANTED) msg("已开始本地记录；系统通知关闭时无法弹出提醒");
    }
  }

  private void startWatch() {
    prefs.edit().putBoolean("watch", true).apply();
    try {
      startForegroundService(new Intent(this, WatchService.class));
    } catch (RuntimeException e) {
      prefs.edit().putBoolean("watch", false).apply();
      msg("系统未允许启动持续记录");
    }
    render();
  }

  private void applyLimits(int lo, int hi, int hot) {
    SharedPreferences.Editor edit =
        prefs.edit().putInt("low", lo).putInt("high", hi).putInt("heat", hot);
    if (prefs.getInt("phase", ChargePhase.FILL) == ChargePhase.MANUAL
        && (lo != prefs.getInt("low", 50) || hi != prefs.getInt("high", 75))) {
      edit.putInt("phase", ChargePhase.WAIT);
      msg("已改用新电量区间，本次提前补电已结束");
    }
    if (lo != prefs.getInt("low", 50) || hi != prefs.getInt("high", 75)
        || hot != prefs.getInt("heat", 40)) {
      BoostPlan.clear(edit);
      getSystemService(NotificationManager.class).cancel(BoostPlan.NOTICE);
    }
    edit.apply();
    CareWidget.refresh(this);
    refreshWatch();
    render();
  }

  private void configure() {
    LinearLayout panel = box();
    panel.setPadding(px(20), 0, px(20), 0);
    EditText l = input(panel, "恢复提醒电量（%）", prefs.getInt("low", 50)),
        u = input(panel, "上限提醒电量（%）", prefs.getInt("high", 75)),
        t = input(panel, "高温提醒（°C）", prefs.getInt("heat", 40));
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("电量与温度阈值")
            .setView(DisplayLayout.scrollForm(panel))
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    d.show();
    d.getButton(-1)
        .setOnClickListener(
            v -> {
              try {
                int lo = Integer.parseInt(l.getText().toString()),
                    hi = Integer.parseInt(u.getText().toString()),
                    hot = Integer.parseInt(t.getText().toString());
                if (lo < 0 || hi > 100 || lo >= hi || hot < 20 || hot > 65)
                  throw new NumberFormatException();
                d.dismiss();
                applyLimits(lo, hi, hot);
              } catch (NumberFormatException ex) {
                msg("电量范围为 0–100%，下限须小于上限。温度范围为 20–65°C。");
              }
            });
  }

  private EditText input(LinearLayout p, String label, int n) {
    text(p, label, 14, ink, false);
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_NUMBER);
    e.setText(String.valueOf(n));
    p.addView(e);
    return e;
  }

  private void msg(String m) {
    Toast.makeText(this, m, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onActivityResult(int code, int result, Intent d) {
    super.onActivityResult(code, result, d);
    if (code != 9 || result != RESULT_OK || d == null || d.getData() == null) return;
    Uri uri = d.getData();
    files.execute(
        () -> {
          try (Archive store = new Archive(getApplicationContext());
              OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            store.exportCsv(out);
            runOnUiThread(() -> msg("已导出记录"));
          } catch (IOException | RuntimeException e) {
            runOnUiThread(() -> msg("无法写入文件"));
          }
        });
  }

  private final class Graph extends View {
    ArrayList<PointF> data = new ArrayList<>();
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Graph() {
      super(MainActivity.this);
      setContentDescription("最近一小时电量趋势");
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight() - 4;
      paint.setStrokeWidth(px(1));
      paint.setColor(getColor(R.color.chart_grid));
      for (int i = 0; i < 3; i++) c.drawLine(0, 2 + i * h / 2, w, 2 + i * h / 2, paint);
      paint.setColor(getColor(R.color.chart_line));
      paint.setStrokeWidth(px(2));
      PointF last = null;
      for (PointF p : data) {
        if (p != null) {
          float y = 2 + (1 - p.y) * h;
          if (last != null) c.drawLine(last.x * w, 2 + (1 - last.y) * h, p.x * w, y, paint);
          else c.drawCircle(p.x * w, y, px(2), paint);
        }
        last = p;
      }
    }
  }
}
