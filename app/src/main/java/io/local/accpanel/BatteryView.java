package io.local.accpanel;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Phone dashboard. All reads and writes are delegated to the activity. */
public final class BatteryView {
    public interface Actions {
        void refresh();
        void apply(int lower, int upper);
        void guide();
        void history();
        void bundledAcc();
        void onApplyTemperature(int resume, int pause);
        void toggleCableHold();
        void reloadCableHold();
        void toggleManualResume();
        void startDaemon();
        void schedule();
    }

    public final View root;
    private final Activity activity;
    private final Actions actions;
    private final boolean dark;
    private final int bg, surface, ink, quiet, line, accent, washed;
    private static final int HERO = 0xff163d34;
    private static final int MINT = 0xffb9f2bd;
    private static final int PALE = 0xffd7eadf;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private TextView percentage, charge, supply, temp, currentValue, condition, driver, configured, diagnostics;
    private TextView voltageValue, powerValue, flowText, policyText, thermalState, markLegend;
    private TextView cableHoldState;
    private Button cableHoldButton, cableHoldReload, startDaemon;
    private Switch manualResumeSwitch;
    private TextView manualResumeHint;
    private Button schedule;
    private TextView scheduleHint;
    private String latestTemp = "—";
    private ScrollView dashboardPage, controlsPage;
    private Button dashboardTab, controlsTab, reload, diagnosticsButton, settingsButton, limitEntry;
    private boolean settingsExpanded;
    private Meter meter;
    private int deviceLower = -1, deviceUpper = -1;
    private PairEditor chargeEditor, temperatureEditor;
    private boolean writable, working;

    public BatteryView(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = color(0xfff3f5ef, 0xff121815);
        surface = color(0xffffffff, 0xff1e2723);
        ink = color(0xff192b23, 0xffedf5ed);
        quiet = color(0xff51645a, 0xffb4c7bb);
        line = color(0xffdbe2da, 0xff38453d);
        accent = color(0xff255f44, 0xffb7eac2);
        washed = color(0xffdfebdf, 0xff33473a);

        LinearLayout shell = column();
        shell.setBackgroundColor(bg);
        LinearLayout top = column();
        top.setPadding(dp(18), dp(8), dp(18), dp(10));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(activity.getString(R.string.app_name), 26, ink, true), weight());
        reload = actionButton("刷新", washed, accent);
        reload.setContentDescription("刷新电池状态和 ACC 配置");
        reload.setOnClickListener(v -> actions.refresh());
        header.addView(reload);
        top.addView(header, block());
        gap(top, 8);
        LinearLayout switcher = row();
        switcher.setPadding(dp(3), dp(3), dp(3), dp(3));
        switcher.setBackground(shape(washed, 14, 0));
        dashboardTab = actionButton("仪表盘", washed, accent);
        controlsTab = actionButton("充电控制", washed, accent);
        dashboardTab.setOnClickListener(v -> selectPage(false));
        controlsTab.setOnClickListener(v -> selectPage(true));
        switcher.addView(dashboardTab, weight());
        switcher.addView(controlsTab, weight());
        top.addView(switcher, block());
        shell.addView(top, block());

        LinearLayout dashboard = column();
        addHero(dashboard);
        gap(dashboard, 12);
        addVitals(dashboard);
        gap(dashboard, 12);
        addElectrical(dashboard);
        gap(dashboard, 8);
        addFooter(dashboard);
        dashboardPage = page(dashboard);
        LinearLayout controls = column();
        addControls(controls);
        Button guide = actionButton("养护指南与初始设置", bg, accent);
        guide.setOnClickListener(v -> actions.guide());
        gap(controls, 8);
        controls.addView(guide, block());
        Button module = actionButton("内置 ACC 模块", bg, accent);
        module.setOnClickListener(v -> actions.bundledAcc());
        controls.addView(module, block());
        controlsPage = page(controls);
        shell.addView(dashboardPage, new LinearLayout.LayoutParams(MATCH, 0, 1));
        shell.addView(controlsPage, new LinearLayout.LayoutParams(MATCH, 0, 1));
        selectPage(false);
        root = shell;
        render("—", "正在读取", "—", "—", "—", "—", "正在读取", "—", "—", false, true);
    }

    private ScrollView page(LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content.setPadding(dp(18), dp(2), dp(18), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        return scroll;
    }

    private void selectPage(boolean controls) {
        controlsPage.setVisibility(controls ? View.VISIBLE : View.GONE);
        dashboardPage.setVisibility(controls ? View.GONE : View.VISIBLE);
        styleTab(controlsTab, controls);
        styleTab(dashboardTab, !controls);
    }

    private void openChargeControls() {
        selectPage(true);
        controlsPage.post(() -> controlsPage.scrollTo(0, 0));
    }

    private void styleTab(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? accent : quiet);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888888),
                shape(selected ? surface : washed, 11, 0), null));
    }

    private void addHero(LinearLayout body) {
        LinearLayout hero = column();
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.setBackground(shape(HERO, 20, 0));
        LinearLayout state = row();
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.addView(label("当前电量", 14, PALE, true), weight());
        charge = label("—", 13, Color.WHITE, true);
        charge.setBackground(shape(0xff2b5548, 12, 0));
        charge.setPadding(dp(10), dp(5), dp(10), dp(5));
        state.addView(charge);
        hero.addView(state, block());
        percentage = label("—", 48, Color.WHITE, true);
        percentage.setIncludeFontPadding(false);
        percentage.setLetterSpacing(-0.025f);
        LinearLayout reading = row();
        reading.setGravity(Gravity.CENTER_VERTICAL);
        reading.addView(percentage, weight());
        limitEntry = actionButton("上限  ›", 0xff285345, MINT);
        limitEntry.setVisibility(View.GONE);
        limitEntry.setOnClickListener(v -> openChargeControls());
        reading.addView(limitEntry);
        hero.addView(reading, block());
        gap(hero, 10);
        meter = new Meter();
        hero.addView(meter, new LinearLayout.LayoutParams(MATCH, dp(23)));
        markLegend = label("", 12, PALE, false);
        markLegend.setVisibility(View.GONE);
        hero.addView(markLegend, block());
        gap(hero, 6);
        LinearLayout foot = row();
        foot.setGravity(Gravity.CENTER_VERTICAL);
        supply = label("—", 13, PALE, false);
        foot.addView(supply, weight());
        Button history = actionButton("查看趋势", 0xff285345, MINT);
        history.setOnClickListener(v -> actions.history());
        foot.addView(history);
        hero.addView(foot, block());
        body.addView(hero, block());
    }

    private void addVitals(LinearLayout body) {
        LinearLayout panel = card(14);
        LinearLayout metrics = row();
        metrics.setBaselineAligned(false);
        temp = metric(metrics, "电池温度");
        dividerBetween(metrics);
        currentValue = metric(metrics, "电池电流");
        dividerBetween(metrics);
        condition = metric(metrics, "健康状态");
        panel.addView(metrics, block());
        body.addView(panel, block());
    }

    private void addElectrical(LinearLayout body) {
        LinearLayout panel = card(16);
        panel.addView(label("供电流向", 19, ink, true));
        gap(panel, 8);
        flowText = label("方向未知", 16, accent, true);
        flowText.setPadding(dp(12), dp(9), dp(12), dp(9));
        flowText.setBackground(shape(washed, 10, 0));
        panel.addView(flowText, block());
        gap(panel, 12);
        LinearLayout metrics = row();
        metrics.setBaselineAligned(false);
        voltageValue = metric(metrics, "电池端电压");
        dividerBetween(metrics);
        powerValue = metric(metrics, "电池端功率（估算）");
        panel.addView(metrics, block());
        body.addView(panel, block());
        gap(body, 12);
        LinearLayout explanation = card(16);
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("当前充电策略", 19, ink, true), weight());
        Button editPolicy = actionButton("调整策略", washed, accent);
        editPolicy.setOnClickListener(v -> openChargeControls());
        heading.addView(editPolicy);
        explanation.addView(heading, block());
        gap(explanation, 6);
        policyText = label("ACC 策略读取中", 15, quiet, false);
        explanation.addView(policyText, block());
        body.addView(explanation, block());
    }

    public void setElectrical(String voltage, String batteryPower, String flow, String policy) {
        voltageValue.setText(display(voltage));
        powerValue.setText(display(batteryPower));
        flowText.setText(display(flow));
        policyText.setText(display(policy));
    }

    private TextView metric(LinearLayout metrics, String name) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.addView(label(name, 12, quiet, false));
        gap(cell, 4);
        TextView value = label("—", 17, ink, true);
        value.setGravity(Gravity.CENTER);
        cell.addView(value, block());
        metrics.addView(cell, weight());
        return value;
    }

    private void dividerBetween(LinearLayout metrics) {
        View separator = new View(activity);
        separator.setBackgroundColor(line);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(1), dp(36));
        size.gravity = Gravity.CENTER_VERTICAL;
        metrics.addView(separator, size);
    }

    private void addControls(LinearLayout body) {
        LinearLayout panel = card(16);
        panel.addView(label("充电上限与回落", 21, ink, true));
        gap(panel, 5);
        driver = label("—", 13, quiet, false);
        configured = label("—", 14, ink, false);
        panel.addView(driver, block());
        configured.setMaxLines(2);
        configured.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(configured, block());
        settingsButton = actionButton("查看完整设置", surface, accent);
        settingsButton.setVisibility(View.GONE);
        settingsButton.setOnClickListener(v -> {
            settingsExpanded = !settingsExpanded;
            configured.setMaxLines(settingsExpanded ? Integer.MAX_VALUE : 2);
            settingsButton.setText(settingsExpanded ? "收起完整设置" : "查看完整设置");
        });
        panel.addView(settingsButton);
        startDaemon = actionButton("启动 ACC 守护进程", washed, accent);
        startDaemon.setVisibility(View.GONE);
        startDaemon.setOnClickListener(v -> actions.startDaemon());
        panel.addView(startDaemon);
        gap(panel, 12);
        chargeEditor = new PairEditor(panel, false);
        gap(panel, 16);
        panel.addView(label("跨插拔保持停充", 18, ink, true), block());
        gap(panel, 4);
        cableHoldState = label("等待读取 ACC 配置。", 14, quiet, false);
        panel.addView(cableHoldState, block());
        gap(panel, 6);
        cableHoldButton = actionButton("开启跨插拔保持停充", washed, accent);
        cableHoldButton.setEnabled(false);
        cableHoldButton.setOnClickListener(v -> actions.toggleCableHold());
        panel.addView(cableHoldButton, block());
        cableHoldReload = actionButton("重新载入并沿用已保存阶段", washed, accent);
        cableHoldReload.setOnClickListener(v -> actions.reloadCableHold());
        cableHoldReload.setVisibility(View.GONE);
        panel.addView(cableHoldReload, block());
        gap(panel, 10);
        manualResumeSwitch = new Switch(activity);
        manualResumeSwitch.setText("本次继续充电");
        manualResumeSwitch.setTextSize(17);
        manualResumeSwitch.setTextColor(ink);
        manualResumeSwitch.setMinHeight(dp(48));
        manualResumeSwitch.setOnClickListener(v -> actions.toggleManualResume());
        panel.addView(manualResumeSwitch, block());
        manualResumeHint = label("", 14, quiet, false);
        panel.addView(manualResumeHint, block());
        schedule = actionButton("预约单次补电", washed, accent);
        schedule.setOnClickListener(v -> actions.schedule());
        panel.addView(schedule, block());
        scheduleHint = label("", 14, quiet, false);
        panel.addView(scheduleHint, block());
        body.addView(panel, block());

        gap(body, 12);
        LinearLayout thermal = card(16);
        thermal.addView(label("温度保护", 21, ink, true));
        gap(thermal, 5);
        thermalState = label("电池温度待获取", 14, accent, true);
        thermalState.setPadding(dp(11), dp(8), dp(11), dp(8));
        thermalState.setBackground(shape(washed, 10, 0));
        thermal.addView(thermalState, block());
        gap(thermal, 12);
        temperatureEditor = new PairEditor(thermal, true);
        body.addView(thermal, block());
    }

    private final class PairEditor {
        private final boolean thermal;
        private final String unit;
        private final int presetLow, presetHigh;
        private int lower, upper;
        private Integer savedLower, savedUpper;
        private final TextView lowValue, highValue, summary;
        private final SeekBar lowBar, highBar;
        private final Button commit, preset;

        PairEditor(LinearLayout panel, boolean thermal) {
            this.thermal = thermal;
            unit = thermal ? "°C" : "%";
            presetLow = thermal ? 35 : 45;
            presetHigh = thermal ? 38 : 55;
            lower = presetLow;
            upper = presetHigh;
            highValue = valueLine(panel, thermal ? "暂停温度" : "停充上限");
            highBar = bar(panel, false);
            lowValue = valueLine(panel, thermal ? "恢复温度" : "自然回落幅度");
            lowBar = bar(panel, true);
            summary = label("", 14, quiet, false);
            if (!thermal) panel.addView(summary, block());
            gap(panel, 4);
            LinearLayout buttons = row();
            preset = actionButton(presetLow + "–" + presetHigh + unit + " 预设", washed, accent);
            preset.setContentDescription("将待应用区间设为 " + presetLow + " 至 " + presetHigh + unit);
            preset.setOnClickListener(v -> setDraft(presetLow, presetHigh));
            buttons.addView(preset);
            LinearLayout.LayoutParams commitSize = weight();
            commitSize.leftMargin = dp(8);
            commit = actionButton("应用区间", accent, color(Color.WHITE, 0xff173824));
            commit.setOnClickListener(v -> {
                if (writable && !working && hasChanges() && validDraft()) {
                    if (thermal) actions.onApplyTemperature(lower, upper);
                    else actions.apply(lower, upper);
                }
            });
            buttons.addView(commit, commitSize);
            panel.addView(buttons, block());
            setDraft(lower, upper);
        }

        private TextView valueLine(LinearLayout panel, String title) {
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label(title, 15, ink, true), weight());
            TextView number = label("—", 17, accent, true);
            row.addView(number);
            panel.addView(row, block());
            return number;
        }

        private SeekBar bar(LinearLayout panel, boolean isLower) {
            SeekBar bar = new SeekBar(activity);
            bar.setMax(thermal ? (isLower ? 49 : 50) : (isLower ? 95 : 100));
            bar.setMin(thermal ? (isLower ? 20 : 35) : (isLower ? 1 : 6));
            bar.setPadding(dp(10), 0, dp(10), 0);
            bar.setContentDescription(thermal ? (isLower ? "恢复温度" : "暂停温度")
                    : (isLower ? "自然回落幅度，百分点" : "停止充电上限，百分比"));
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(line));
            bar.setThumbTintList(ColorStateList.valueOf(accent));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    if (isLower) {
                        lower = thermal ? progress : upper - progress;
                    } else {
                        int previousDifference = upper - lower;
                        upper = progress;
                        if (!thermal) lower = Math.max(5, upper - previousDifference);
                    }
                    setDraft(lower, upper);
                }
            });
            panel.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(48)));
            return bar;
        }

        void setSaved(int lower, int upper) {
            if (lower >= upper || lower < (thermal ? 20 : 5) || upper < highBar.getMin()
                    || upper > highBar.getMax()) return;
            savedLower = lower;
            savedUpper = upper;
            setDraft(lower, upper);
        }

        private void setDraft(int lower, int upper) {
            this.lower = lower;
            this.upper = upper;
            if (!thermal) lowBar.setMax(upper - 5);
            lowBar.setProgress(thermal ? lower : upper - lower);
            highBar.setProgress(upper);
            update();
        }

        private void updateThermalState(boolean showDraft) {
            String prefix = "当前电池温度  " + latestTemp;
            int low, high;
            if (showDraft && validDraft()) {
                low = lower;
                high = upper;
            } else {
                thermalState.setText(prefix);
                return;
            }
            try {
                float t = Float.parseFloat(latestTemp.replace("°C", "").trim());
                String position = t >= high ? "已达暂停阈值"
                        : t <= low ? "已降至恢复阈值以内" : "位于两阈值之间";
                String context = hasChanges() ? "按预览设置" : "按当前设置";
                thermalState.setText(prefix + "  ·  " + context + "，" + position);
            } catch (NumberFormatException ignored) {
                thermalState.setText(prefix);
            }
        }

        private boolean validDraft() { return lower < upper; }
        private boolean hasChanges() {
            return savedLower == null || savedUpper == null || savedLower != lower || savedUpper != upper;
        }

        void update() {
            boolean editing = writable && !working;
            boolean active = editing && validDraft() && hasChanges();
            boolean showDraft = writable || (working && savedLower != null);
            String lowDisplay = thermal ? lower + unit : (upper - lower) + " 个百分点";
            lowValue.setText(showDraft ? lowDisplay : "—");
            if (thermal) updateThermalState(showDraft);
            else summary.setText(showDraft ? "恢复电量设为 " + lower + "%。" : "恢复电量待 ACC 回读。");
            highValue.setText(showDraft ? upper + unit : "—");
            lowBar.setVisibility(showDraft ? View.VISIBLE : View.INVISIBLE);
            highBar.setVisibility(showDraft ? View.VISIBLE : View.INVISIBLE);
            commit.setEnabled(active);
            commit.setAlpha(active ? 1f : 0.65f);
            preset.setEnabled(editing);
            preset.setAlpha(editing ? 1f : 0.65f);
            lowBar.setEnabled(editing);
            highBar.setEnabled(editing);
            String title = thermal ? "应用温控" : "应用区间";
            if (working) title = "处理中";
            else if (!writable) title = "仅可查看";
            else if (!validDraft()) title = "下限须小于上限";
            else if (!hasChanges()) title = "已应用";
            commit.setText(title);
        }
    }

    private void addFooter(LinearLayout body) {
        LinearLayout footer = row();
        Button guide = actionButton("养护指南", bg, accent);
        guide.setOnClickListener(v -> actions.guide());
        diagnosticsButton = actionButton("展开诊断", bg, quiet);
        diagnostics = label("", 13, quiet, false);
        diagnostics.setTypeface(Typeface.MONOSPACE);
        diagnostics.setTextIsSelectable(true);
        diagnostics.setBackground(shape(surface, 12, line));
        diagnostics.setPadding(dp(14), dp(14), dp(14), dp(14));
        diagnostics.setVisibility(View.GONE);
        diagnosticsButton.setOnClickListener(v -> {
            boolean show = diagnostics.getVisibility() != View.VISIBLE;
            diagnostics.setVisibility(show ? View.VISIBLE : View.GONE);
            diagnosticsButton.setText(show ? "收起诊断" : "展开诊断");
        });
        footer.addView(guide, weight());
        footer.addView(diagnosticsButton, weight());
        body.addView(footer, block());
        body.addView(diagnostics, block());
    }

    public void render(String percent, String charging, String power, String temperature,
                       String current, String health, String controller, String settings,
                       String details, boolean canApply, boolean busy) {
        String p = display(percent).replace("%", "").trim();
        int level;
        try {
            level = Integer.parseInt(p);
        } catch (NumberFormatException ignored) {
            level = -1;
        }
        boolean known = level >= 0 && level <= 100;
        percentage.setText(known ? level + "%" : "—");
        meter.setLevel(known ? level : -1);
        charge.setText(display(charging));
        supply.setText("电源  ·  " + display(power));
        latestTemp = display(temperature);
        temp.setText(latestTemp);
        currentValue.setText(display(current));
        condition.setText(display(health));
        driver.setText(display(controller));
        String config = display(settings);
        configured.setText(config);
        boolean longConfig = config.length() > 38 || config.split("\\n", -1).length > 2;
        settingsButton.setVisibility(longConfig ? View.VISIBLE : View.GONE);
        configured.setMaxLines(longConfig && !settingsExpanded ? 2 : Integer.MAX_VALUE);
        diagnostics.setText(display(details));
        writable = canApply;
        working = busy;
        boolean markersAvailable = canApply && deviceLower >= 0 && deviceLower < deviceUpper && deviceUpper <= 100;
        if (markersAvailable) {
            meter.setMarks(deviceLower, deviceUpper);
            markLegend.setText("虚线  " + deviceLower + "% 恢复    ·    实线  " + deviceUpper + "% 停充");
            limitEntry.setText("上限 " + deviceUpper + "%  ›");
            limitEntry.setContentDescription("停充上限 " + deviceUpper + "%，打开充电控制");
        } else {
            meter.setMarks(-1, -1);
        }
        markLegend.setVisibility(markersAvailable ? View.VISIBLE : View.GONE);
        limitEntry.setVisibility(markersAvailable ? View.VISIBLE : View.GONE);
        reload.setEnabled(!busy);
        reload.setText(busy ? "处理中" : "刷新");
        chargeEditor.update();
        temperatureEditor.update();
    }

    public void setThresholds(int lower, int upper) {
        deviceLower = lower;
        deviceUpper = upper;
        chargeEditor.setSaved(lower, upper);
    }

    public void setTemperatures(int resume, int pause) { temperatureEditor.setSaved(resume, pause); }

    public void setDaemonStopped(boolean stopped) {
        startDaemon.setVisibility(stopped ? View.VISIBLE : View.GONE);
        startDaemon.setEnabled(stopped && !working);
    }

    public void setCableHold(Boolean enabled, boolean loaded, boolean holding, boolean temperatureOverridesCapacity,
                             boolean manualResume, int level, String checked, String window) {
        boolean on = Boolean.TRUE.equals(enabled);
        String text;
        if (enabled == null) {
            text = "尚未读到兼容的 ACC 插件环境。";
        } else if (on) {
            text = "读取于 " + checked + " · " + (loaded ? "插件已载入" : "插件尚未在当前守护进程中载入")
                    + "。已保存阶段：" + (manualResume ? "本次提前补至原上限。" : holding ? "等恢复下限。" : "允许充向上限。");
        } else {
            text = "已关闭。开启会安装归根的 ACC 插件并重启 ACC 守护进程。";
            if (deviceLower >= 0 && deviceUpper <= 100 && deviceLower < deviceUpper)
                text += "到 " + deviceUpper + "% 停充后，即使拔线再接，也要降至 " + deviceLower
                        + "% 或以下且满足温控才复充。";
        }
        if (enabled != null && temperatureOverridesCapacity)
            text += " 当前恢复温度带 r，允许越过电量下限，与此策略冲突。";
        cableHoldState.setText(text);
        cableHoldButton.setText(on ? "关闭跨插拔保持停充" : "开启跨插拔保持停充");
        boolean canEnable = writable && !working && !temperatureOverridesCapacity;
        boolean available = !working && enabled != null && (on || canEnable);
        cableHoldButton.setEnabled(available);
        cableHoldButton.setAlpha(available ? 1f : 0.65f);
        cableHoldReload.setVisibility(on && !loaded ? View.VISIBLE : View.GONE);
        cableHoldReload.setEnabled(canEnable);
        manualResumeSwitch.setChecked(manualResume);
        boolean inBand = level > deviceLower && level < deviceUpper;
        boolean planned = !window.isEmpty();
        schedule.setText(planned ? "取消本轮预约" : "预约单次补电");
        schedule.setEnabled(!working && (planned || (canEnable && on && loaded && holding && inBand)));
        scheduleHint.setText(planned ? "已预约 " + window + "。到原上限或窗口结束恢复巡航。"
                : "等待回落时可选一个开始时间，届时温控允许才提前补电，窗口为 1 小时。");
        manualResumeSwitch.setEnabled(!working && (manualResume || (canEnable && on && loaded && holding && inBand)));
        String hint;
        if (manualResume) hint = "本次允许补到 " + deviceUpper + "%，到上限自动关闭。提前关闭会恢复等待下限。";
        else if (!on) hint = "开启跨插拔保持后，可以在回落途中提前补一次电。";
        else if (!loaded) hint = "先重新载入巡航插件，再使用本次补电。";
        else if (level < 0) hint = "尚未读到 ACC 控制使用的电量，请刷新后重试。";
        else if (level >= deviceUpper) hint = "驱动电量已到上限，回落到区间内后可手动补电。";
        else if (temperatureOverridesCapacity) hint = "当前温度恢复规则与严格回落冲突。";
        else if (holding) hint = "提前补到原上限 " + deviceUpper + "%，全程沿用 ACC 温控。";
        else hint = "当前已允许向上充电，无需提前补电。";
        manualResumeHint.setText(hint);
    }

    private class Meter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int level = -1, lower = -1, upper = -1;
        Meter() {
            super(activity);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setLevel(int value) { level = value; invalidate(); }
        void setMarks(int lower, int upper) {
            if (this.lower == lower && this.upper == upper) return;
            this.lower = lower;
            this.upper = upper;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int segments = 40;
            float space = dp(2);
            float unit = (getWidth() - space * (segments - 1)) / segments;
            if (unit <= 0) return;
            for (int i = 0; i < segments; i++) {
                float x = i * (unit + space);
                rect.set(x, dp(5), x + unit, getHeight() - dp(5));
                paint.setColor(0xff396052);
                canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                float fill = Math.min(1f, Math.max(0f, level * segments / 100f - i));
                if (fill > 0) {
                    canvas.save();
                    canvas.clipRect(x, 0, x + unit * fill, getHeight());
                    paint.setColor(MINT);
                    canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                    canvas.restore();
                }
            }
            if (lower >= 0 && upper >= 0) {
                drawMark(canvas, lower, true);
                drawMark(canvas, upper, false);
            }
        }
        private void drawMark(Canvas canvas, int percent, boolean dashed) {
            float x = Math.max(dp(1), Math.min(getWidth() - dp(1), percent / 100f * getWidth()));
            int end = getHeight() - dp(1);
            for (int y = dp(1); y < end; y += dashed ? dp(8) : getHeight()) {
                int bottom = dashed ? Math.min(y + dp(5), end) : end;
                paint.setColor(HERO);
                paint.setStrokeWidth(dp(4));
                canvas.drawLine(x, y, x, bottom, paint);
                paint.setColor(dashed ? Color.WHITE : 0xffffcb78);
                paint.setStrokeWidth(dp(2));
                canvas.drawLine(x, y, x, bottom, paint);
            }
        }
    }

    private LinearLayout card(int padding) {
        LinearLayout card = column();
        card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        card.setBackground(shape(surface, 18, line));
        return card;
    }

    private Button actionButton(String title, int fill, int foreground) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888),
                shape(fill, 12, 0), null));
        return button;
    }

    private TextView label(String text, int size, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() { return new LinearLayout(activity); }
    private void gap(LinearLayout column, int size) {
        column.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(size)));
    }
    private static LinearLayout.LayoutParams block() { return new LinearLayout.LayoutParams(MATCH, WRAP); }
    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, WRAP, 1); }
    private int color(int light, int night) { return dark ? night : light; }
    private int dp(float raw) { return Math.round(raw * activity.getResources().getDisplayMetrics().density); }
    private static String display(String s) { return s == null || s.trim().isEmpty() ? "—" : s.trim(); }
}
