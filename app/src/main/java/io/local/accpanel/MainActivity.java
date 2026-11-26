package io.local.accpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements BatteryView.Actions {
    private static final int READ = 0, CAPACITY = 1, TEMPERATURE = 2, CABLE_HOLD = 3, READ_PHASE = 4;
    private static final long SAMPLE_INTERVAL_MS = 10_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final History history = new History();
    private final Runnable sampleAgain = new Runnable() {
        @Override public void run() {
            if (refreshPhase && !busy && !readingPhase) {
                refreshPhase = false;
                if (state != null && Boolean.TRUE.equals(state.cableHold)) MainActivity.this.run(READ_PHASE, 0, 0);
            }
            int oldLevel = batteryLevel, oldPlug = plugged;
            sample();
            if (state != null && Boolean.TRUE.equals(state.cableHold)
                    && (oldPlug != plugged || thresholdBand(oldLevel) != thresholdBand(batteryLevel)))
                refreshPhase = true;
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };
    private BatteryView ui;
    private BatteryManager battery;
    private AccBackend.State state;
    private boolean busy;
    private boolean refreshPhase, readingPhase;
    private String problem = "", checked = "";
    private String percent = "—", charging = "未知", power = "未知", temperature = "—", current = "—", health = "未知";
    private String voltage = "—";
    private String batteryWatts = "—";
    private int batteryLevel = -1, plugged = -1, chargeStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
    private float cellTemperature = Float.NaN;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        battery = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        ui = new BatteryView(this, this);
        setContentView(ui.root);
        refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        refreshPhase = state != null && Boolean.TRUE.equals(state.cableHold);
        sampleAgain.run();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(sampleAgain);
        super.onStop();
    }

    @Override protected void onDestroy() {
        // An already submitted ACC operation must finish its readback.
        worker.shutdown();
        super.onDestroy();
    }

    private void sample() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1, mv = 0;
        float t = Float.NaN, ma = Float.NaN;
        plugged = -1;
        chargeStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
        if (b != null) {
            int raw = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            if (raw >= 0 && scale > 0 && raw <= scale) level = Math.round(100f * raw / scale);
            if (b.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
                t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            chargeStatus = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            health = condition(b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
        } else health = "未知";
        if (battery != null) {
            int ua = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (ua != Integer.MIN_VALUE) ma = ua / 1000f;
        }
        batteryLevel = level;
        cellTemperature = t;
        charging = status(chargeStatus);
        power = source(plugged);
        voltage = mv > 0 ? String.format(Locale.ROOT, "%.3f V", mv / 1000f) : "—";
        batteryWatts = mv > 0 && !Float.isNaN(ma)
                ? String.format(Locale.ROOT, "%.2f W", Math.abs(ma) * mv / 1_000_000d) : "—";
        percent = level < 0 ? "—" : String.valueOf(level);
        temperature = Float.isNaN(t) ? "—" : String.format(Locale.ROOT, "%.1f °C", t);
        current = Float.isNaN(ma) ? "—" : String.format(Locale.ROOT, "%+.0f mA", ma);
        history.add(System.currentTimeMillis(), level, t, ma);
        render();
    }

    private static String status(int code) {
        switch (code) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "充电中";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "放电中";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "未充电";
            case BatteryManager.BATTERY_STATUS_FULL: return "已充满";
            default: return "未知";
        }
    }

    private static String source(int code) {
        switch (code) {
            case 0: return "使用电池";
            case BatteryManager.BATTERY_PLUGGED_AC: return "电源适配器";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "无线充电";
            case BatteryManager.BATTERY_PLUGGED_DOCK: return "底座供电";
            default: return "未知";
        }
    }

    private static String condition(int code) {
        switch (code) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "正常";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "过热";
            case BatteryManager.BATTERY_HEALTH_COLD: return "过冷";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "失效";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "过压";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "故障";
            default: return "未知";
        }
    }

    private boolean canWrite() {
        return state != null && state.lower <= 100 && state.upper <= 100 && "运行中".equals(state.daemon);
    }

    private int thresholdBand(int level) {
        if (level < 0) return -1;
        if (level <= state.lower) return 0;
        return level >= state.upper ? 2 : 1;
    }

    private String powerFlow() {
        if (plugged < 0) return "供电来源未知";
        if (plugged == 0) return "电池 → 手机";
        switch (chargeStatus) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "外部电源 → 手机与电池";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "外部电源已连接 · 电池正在放电";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "外部电源已连接 · 电池未充电";
            case BatteryManager.BATTERY_STATUS_FULL: return "外部电源已连接 · 电池已充满";
            default: return "外部电源已连接 · 充电状态未知";
        }
    }

    private String policyDescription() {
        if (state == null) return busy ? "正在向 ACC 读取充电上限和恢复条件。"
                : getString(R.string.root_authorization_hint, getString(R.string.app_name));
        if (!"运行中".equals(state.daemon)) return "ACC 守护进程状态为「" + state.daemon + "」。已保存的阈值可能尚未执行。";
        if (cellTemperature >= state.maxTemp)
            return "电池温度已达到 " + state.maxTemp + "°C 暂停线。降至 " + state.resumeTemp + "°C 并满足其他条件后才允许恢复。";
        if (state.upper > 100 || state.lower > 100) return "ACC 正在使用电压阈值。";
        if (plugged == 0) return "正在使用电池。上限为 " + state.upper + "%，恢复电量设为 " + state.lower + "%。";
        if (plugged > 0 && batteryLevel >= state.upper && chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING)
            return "已达到 " + state.upper + "% 停充线，Android 仍报告充电中。可在趋势中继续观察。";
        if (plugged > 0 && chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING)
            return "Android 报告正在充电。配置的停充上限为 " + state.upper + "%。";
        if (plugged > 0 && chargeStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            if (state.resumeOverridesCapacity)
                return "Android 报告未充电。恢复温度带 r，达到该温度时 ACC 可越过电量下限。";
            return "Android 报告未充电。停充上限为 " + state.upper + "%，恢复电量设为 " + state.lower + "%。";
        }
        return "停充上限为 " + state.upper + "%，恢复电量设为 " + state.lower + "%。";
    }

    private void render() {
        String driver = "ACC · " + (busy ? "正在读取手机配置" : "未连接");
        String settings = busy ? "正在等待 ACC 响应…" : "授权 root 并启用 ACC 后，点「刷新」读取配置。";
        String details = Build.MODEL + " · Android " + Build.VERSION.RELEASE + "\n系统报告电压 " + voltage
                + "\n状态、温度和电流来自 Android。功率取上报电流与电压乘积的绝对值，双电芯的传感器口径可能与整包不同。适配器输入功率未知。健康状态为系统分类。";
        if (state != null) {
            driver = "ACC " + state.version + " · 守护进程" + state.daemon;
            String unit = state.upper > 100 ? " mV" : "%";
            settings = "设备设置   " + state.lower + "–" + state.upper + unit + "  ·  "
                    + state.resumeTemp + "–" + state.maxTemp + " °C";
            if (!canWrite()) settings += "\n此配置仅供查看。请检查 ACC 守护进程及控制单位。";
            String usb = state.usbOnline == null ? "未知" : state.usbOnline == 1 ? "在线" : "离线";
            details += "\n\n读取于 " + checked + "\nUSB 硬件状态（读取时） " + usb
                    + "\n驱动报告的电池循环次数 " + (state.cycleCount == null ? "未知" : state.cycleCount)
                    + "\n" + state.settings;
        }
        if (!problem.isEmpty()) {
            settings = problem;
            details += "\n\n" + problem;
        }
        ui.render(percent, charging, power, temperature, current, health, driver,
                settings, details, canWrite(), busy);
        ui.setCableHold(state == null ? null : state.cableHold, state != null && state.holdLoaded,
                state != null && state.holding, state != null && state.resumeOverridesCapacity, checked);
        String policy = policyDescription();
        if (state != null && Boolean.TRUE.equals(state.cableHold))
            policy += "\n" + checked + " 读回 · " + (state.holding ? "等待恢复下限" : "允许充向上限")
                    + (state.holdLoaded ? "。" : "（阶段已保存，插件待载入）。");
        ui.setElectrical(voltage, batteryWatts, powerFlow(), policy);
    }

    @Override public void refresh() {
        sample();
        run(READ, 0, 0);
    }
    @Override public void apply(int lower, int upper) { run(CAPACITY, lower, upper); }
    @Override public void onApplyTemperature(int resume, int pause) { run(TEMPERATURE, resume, pause); }
    @Override public void toggleCableHold() {
        if (busy || state == null || state.cableHold == null) return;
        if (state.cableHold) {
            run(CABLE_HOLD, 0, 0);
            return;
        }
        if (!canWrite() || state.resumeOverridesCapacity) return;
        sample();
        if (batteryLevel >= state.upper) run(CABLE_HOLD, 1, 1);
        else if (batteryLevel >= 0 && batteryLevel <= state.lower) run(CABLE_HOLD, 1, 0);
        else new AlertDialog.Builder(this).setTitle("选择本轮巡航阶段")
                .setItems(new String[]{"本轮已经到过上限，继续等下限", "本轮正在充向上限"},
                        (dialog, index) -> run(CABLE_HOLD, 1, index == 0 ? 1 : 0))
                .setNegativeButton("取消", null).show();
    }
    @Override public void reloadCableHold() {
        if (!canWrite() || busy || !Boolean.TRUE.equals(state.cableHold) || state.holdLoaded
                || state.resumeOverridesCapacity) return;
        run(CABLE_HOLD, 1, state.holding ? 1 : 0);
    }
    private String readHoldPlugin() throws IOException {
        try (InputStream source = getResources().openRawResource(R.raw.rootward_hold)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = source.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    @Override public void history() { history.show(this); }

    private SharedPreferences original() { return getSharedPreferences("first_read", MODE_PRIVATE); }

    private void run(int action, int first, int second) {
        boolean passive = action == READ_PHASE;
        boolean reading = action == READ || passive;
        if (busy || (!reading && !(action == CABLE_HOLD && first == 0) && !canWrite())) return;
        if (passive) readingPhase = true;
        else {
            busy = true;
            problem = "";
            render();
        }
        worker.execute(() -> {
            try {
                AccBackend.State result;
                if (action == CAPACITY) result = AccBackend.apply(first, second);
                else if (action == TEMPERATURE) result = AccBackend.applyTemperature(first, second);
                else if (action == CABLE_HOLD) result = AccBackend.setCableHold(first == 1, readHoldPlugin(), second == 1);
                else result = AccBackend.read();
                if (!original().contains("lower") && result.upper <= 100 && result.lower <= 100)
                    original().edit().putInt("lower", result.lower).putInt("upper", result.upper)
                            .putInt("resume", result.resumeTemp).putInt("pause", result.maxTemp).apply();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (passive) {
                        readingPhase = false;
                        if (busy) return;
                    }
                    busy = false;
                    AccBackend.State previous = state;
                    state = result;
                    checked = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(result.snapshotMillis));
                    if (previous == null || action == CAPACITY || previous.lower != result.lower || previous.upper != result.upper)
                        ui.setThresholds(result.lower, result.upper);
                    if (previous == null || action == TEMPERATURE || previous.resumeTemp != result.resumeTemp || previous.maxTemp != result.maxTemp)
                        ui.setTemperatures(result.resumeTemp, result.maxTemp);
                    if (!reading) Toast.makeText(this, "已从 ACC 读回并核对新设置", Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception err) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (passive) {
                        readingPhase = false;
                        return;
                    }
                    busy = false;
                    state = null;
                    problem = (action == READ ? "读取失败" : "写入未确认，请刷新核对")
                            + " · " + (err.getMessage() == null ? "ACC 暂不可用" : err.getMessage());
                    render();
                });
            }
        });
    }

    @Override public void guide() {
        String text = "上限与自然回落\n恢复电量等于停充上限减去回落的百分点数。在「充电控制」打开「跨插拔保持停充」后，"
                + "例如设为 50%–75%，ACC 充到 75% 停充，回落到 60% 时拔线再插仍须等到 50% 或以下，并满足温控才允许复充。"
                + "从 50% 开始向上充电经过 60% 时则继续充。首次开启会重启 ACC 以载入插件。插件在后台保存本轮阶段；卸载归根前可先在充电控制里关闭。\n\n"
                + "电池温度\n可先试 35°C 恢复、38°C 暂停。两者都指电池传感器温度。炎热时可能较久无法恢复，电量下限等条件也会影响复充。\n\n"
                + "减少发热\n把手机放在通风阴凉处，重载游戏、导航与充电尽量错开。桌面使用可先试发热较低且功率够用的充电器。\n\n"
                + "观察停充\n到达上限后保持负载稳定，在趋势中观察几分钟。电量平稳且电池净电流持续接近零，说明电池参与供电较少。单次读数无法确认硬件旁路。\n\n"
                + "手机久置\n在约一半电量时关机，放在阴凉干燥处，定期查看余量。日常无需为养护刻意放到自动关机。";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("养护指南").setMessage(text)
                .setPositiveButton("完成", null);
        if (original().contains("lower") && canWrite() && !busy) b.setNeutralButton("恢复初始值…", (d, w) -> restore());
        b.show();
    }

    private void restore() {
        SharedPreferences p = original();
        int lower = p.getInt("lower", -1), upper = p.getInt("upper", -1);
        int resume = p.getInt("resume", -1), pause = p.getInt("pause", -1);
        String[] options = {"电量区间   " + lower + "–" + upper + "%", "温度区间   " + resume + "–" + pause + " °C"};
        new AlertDialog.Builder(this).setTitle("恢复本应用首次读取的设置")
                .setItems(options, (d, w) -> {
                    if (w == 0) apply(lower, upper);
                    else onApplyTemperature(resume, pause);
                }).setNegativeButton("取消", null).show();
    }
}
