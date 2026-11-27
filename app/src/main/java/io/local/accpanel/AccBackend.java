package io.local.accpanel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Synchronous operations for the background worker. Only ACC writes its own settings. */
public final class AccBackend {
    private static final String PROBE =
            "if [ \"$(id -u)\" != 0 ]; then echo '需要 root 授权'; exit 80; fi\n"
            + "for acc in /dev/acc /dev/.vr25/acc/acc /system/bin/acc /system/xbin/acc "
            + "/sbin/acc /data/adb/vr25/acc/acc.sh; do [ -x \"$acc\" ] && break; done\n"
            + "if [ ! -x \"$acc\" ]; then echo '未找到 ACC 命令，请检查模块是否启用'; exit 81; fi\n";

    // Request the necessary fields. ACC configs may also contain the owner's shell scripts.
    private static final String SELECT = "^(resume_capacity|pause_capacity|shutdown_capacity|"
            + "max_temp|resume_temp|shutdown_temp|cooldown_temp|cooldown_capacity|"
            + "cooldown_charge|cooldown_pause|cooldown_current|prioritize_batt_idle_mode|"
            + "allow_idle_above_pcap|force_off|charging_switch|max_charging_current|max_charging_voltage)=";
    private static final String SNAPSHOT =
            "printf '\\n__VERSION__\\n'\n"
            + "\"$acc\" -v || exit $?\n"
            + "\"$acc\" -D >/dev/null 2>&1; daemon_code=$?\n"
            + "printf '\\n__DAEMON__\\n%s\\n__SETTINGS__\\n' \"$daemon_code\"\n"
            + "\"$acc\" -sp '" + SELECT + "' || exit $?\n"
            + StrictHold.SNAPSHOT
            + "cd /sys/class/power_supply || exit 0\n"
            + "for node in usb/online battery/cycle_count bms/cycle_count; do\n"
            + "  if [ -r \"$node\" ]; then printf '\\n@%s=%s\\n' \"$node\" "
            + "\"$(cat \"$node\" 2>/dev/null)\"; fi\n"
            + "done\n";
    private static final String TIMEOUT =
            "ACC 响应超时。请检查 root 授权，刷新后确认当前配置。";

    private AccBackend() {}

    public static final class State {
        public final int lower, upper, resumeTemp, maxTemp;
        /** Configured option, not proof that ACC is currently enforcing a hold. */
        public final Boolean forceOff;
        /** Persisted opt-in (null when unavailable). The PID handshake is reported separately. */
        public final Boolean cableHold;
        public final boolean holding, holdLoaded;
        /** Persisted permission to top up to the previously approved upper threshold once. */
        public final boolean manualResume;
        public final boolean resumeOverridesCapacity;
        public final String version, daemon, settings, details;
        /** Hardware readings taken at snapshotMillis, independent of Android's battery broadcasts. */
        public final Integer usbOnline, cycleCount;
        public final long snapshotMillis;
        private final Map<String, String> fields;

        private State(String output) throws IOException {
            int versionMark = output.indexOf("__VERSION__\n");
            int daemonMark = output.indexOf("__DAEMON__\n", versionMark + 1);
            int settingsMark = output.indexOf("__SETTINGS__\n", daemonMark + 1);
            if (versionMark < 0 || daemonMark <= versionMark || settingsMark <= daemonMark) {
                throw new IOException("无法识别 ACC 的响应，请确认模块版本。");
            }
            version = output.substring(versionMark + "__VERSION__\n".length(), daemonMark).trim();
            String code = output.substring(daemonMark + "__DAEMON__\n".length(), settingsMark).trim();
            daemon = code.equals("0") ? "运行中" : code.equals("9") ? "已停止" : "未知";
            fields = new LinkedHashMap<>();
            StringBuilder report = new StringBuilder();
            for (String line : output.substring(settingsMark + "__SETTINGS__\n".length()).split("\n")) {
                int equals = line.indexOf('=');
                if (equals < 1) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (fields.put(key, value) != null) throw new IOException("ACC 返回了重复字段 " + key);
                if (!key.startsWith("@")) report.append(key).append('=').append(value).append('\n');
            }
            snapshotMillis = System.currentTimeMillis();
            String online = fields.get("@usb/online");
            usbOnline = "1".equals(online) || "0".equals(online) ? Integer.valueOf(online) : null;
            Integer batteryCount = optionalCount("@battery/cycle_count");
            Integer bmsCount = optionalCount("@bms/cycle_count");
            // Some kernels expose a permanent zero or two disagreeing counters. Bin arrays
            // (cycle_counts) use a separate convention and never stand in for full cycles.
            if (batteryCount != null && bmsCount != null) {
                cycleCount = batteryCount.equals(bmsCount) ? batteryCount : null;
            } else {
                Integer only = batteryCount != null ? batteryCount : bmsCount;
                cycleCount = only != null && only > 0 ? only : null;
            }
            lower = number("resume_capacity");
            upper = number("pause_capacity");
            maxTemp = number("max_temp");
            resumeTemp = number("resume_temp");
            resumeOverridesCapacity = fields.get("resume_temp").endsWith("r");
            String configured = fields.get("force_off");
            forceOff = "true".equals(configured) ? Boolean.TRUE
                    : "false".equals(configured) ? Boolean.FALSE : null;
            String holdSetting = fields.get("@hold_setting");
            cableHold = "true".equals(holdSetting) ? Boolean.TRUE
                    : StrictHold.VERSION.equals(version) && "false".equals(holdSetting) ? Boolean.FALSE : null;
            manualResume = Boolean.TRUE.equals(cableHold) && "true".equals(fields.get("@manual_resume"));
            holding = !manualResume && Boolean.TRUE.equals(cableHold) && "true".equals(fields.get("@hold_phase"));
            holdLoaded = StrictHold.VERSION.equals(version) && "运行中".equals(daemon)
                    && "true".equals(fields.get("@hold_loaded"));
            settings = report.toString().trim();
            details = "ACC " + version + " · 守护进程" + daemon;
        }

        private Integer optionalCount(String key) {
            String value = fields.get(key);
            return value != null && value.matches("[0-9]{1,6}") ? Integer.valueOf(value) : null;
        }

        private int number(String key) throws IOException {
            String value = fields.get(key);
            if ("resume_temp".equals(key) && value != null && value.endsWith("r")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value != null && value.matches("[0-9]{1,5}")) return Integer.parseInt(value);
            throw new IOException("ACC 未返回可识别的 " + key + "。");
        }
    }

    public static synchronized State read() throws Exception {
        return snapshot("");
    }

    public static synchronized State apply(int lower, int upper) throws Exception {
        if (lower < 5 || lower >= upper || upper > 100) {
            throw new IOException("恢复电量应在 5% 及以上，低于暂停电量，上限为 100%。");
        }
        State current = read();
        if (current.upper > 100 || current.lower > 100) {
            throw new IOException("现有电量控制采用电压单位，面板仅编辑百分比模式。");
        }
        int shutdown = current.number("shutdown_capacity");
        if (shutdown <= 100 && lower <= shutdown) {
            throw new IOException("恢复电量须高于 ACC 的关机阈值 " + shutdown + "%。");
        }
        State saved = write("resume_capacity=" + lower + " pause_capacity=" + upper);
        if (saved.lower != lower || saved.upper != upper) throw mismatch();
        return saved;
    }

    public static synchronized State applyTemperature(int resume, int pause) throws Exception {
        if (pause < 35 || pause > 50 || resume < 1 || resume >= pause) {
            throw new IOException("暂停温度可设为 35–50°C，恢复温度必须为较低的正整数。");
        }
        State current = read();
        int shutdown = current.number("shutdown_temp");
        if (shutdown > 0 && pause >= shutdown) {
            throw new IOException("暂停温度须低于 ACC 的关机温度 " + shutdown + "°C。");
        }
        // ACC uses an optional r to override resume_capacity. Preserve this existing policy.
        String value = resume + (current.resumeOverridesCapacity ? "r" : "");
        State saved = write("resume_temp=" + value + " max_temp=" + pause);
        if (!value.equals(saved.fields.get("resume_temp")) || saved.maxTemp != pause) throw mismatch();
        return saved;
    }

    public static synchronized State setCableHold(boolean enabled, String pluginBody, boolean seedHold) throws Exception {
        State current = read();
        if (current.cableHold == null) throw new IOException("当前 ACC 版本或插件路径不支持跨插拔保持。");
        if (enabled) {
            if (!StrictHold.VERSION.equals(current.version) || !"运行中".equals(current.daemon)) {
                throw new IOException("请先运行受支持版本的 ACC 守护进程。");
            }
            if (current.lower >= current.upper || current.upper > 100 || current.resumeOverridesCapacity) {
                throw new IOException("跨插拔保持需要有效的百分比区间，且恢复温度不能覆盖电量下限。");
            }
        }
        State saved = snapshot(writeDeadline() + StrictHold.command(enabled, pluginBody, seedHold));
        if (!Boolean.valueOf(enabled).equals(saved.cableHold) || (enabled && !saved.holdLoaded)) throw mismatch();
        return saved;
    }

    public static synchronized State setManualResume(boolean enabled, String pluginBody) throws Exception {
        State current = read();
        if (enabled) {
            if (!Boolean.TRUE.equals(current.cableHold) || !current.holdLoaded) {
                throw new IOException("请先开启严格巡航，并确认 ACC 守护进程已载入插件。");
            }
            if (current.manualResume) return current;
            if (!current.holding || current.resumeOverridesCapacity) {
                throw new IOException("当前未在严格回落阶段，或恢复温度配置覆盖了电量下限。");
            }
        } else if (!current.manualResume) {
            return current;
        }
        State saved = snapshot(writeDeadline() + StrictHold.manualCommand(enabled, pluginBody));
        if (saved.manualResume != enabled) throw mismatch();
        return saved;
    }

    private static State write(String fields) throws Exception {
        return snapshot(writeDeadline() + "\"$acc\" -s " + fields + " || exit $?\n");
    }

    private static String writeDeadline() {
        // Root managers can keep a permission prompt open after the UI times out.
        long expires = System.currentTimeMillis() / 1000 + 40;
        return "if [ \"$(date +%s)\" -ge " + expires
                + " ]; then echo '本次写入已过期，请刷新重试'; exit 82; fi\n";
    }

    private static IOException mismatch() {
        return new IOException("ACC 接收了命令，但回读值与请求不一致。请刷新查看实际值。");
    }

    private static State snapshot(String write) throws Exception {
        String output = runRoot(PROBE + write + SNAPSHOT);
        return new State(output.replace("\r", ""));
    }

    private static String runRoot(String command) throws Exception {
        final Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("未能启动 su。请检查手机的 root 管理器及应用授权。", e);
        }
        FutureTask<String> result = new FutureTask<>(() -> {
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream saved = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (saved.size() + count > 65536) throw new IOException("ACC 输出超过读取上限。");
                    saved.write(buffer, 0, count);
                }
                String output = new String(saved.toByteArray(), StandardCharsets.UTF_8).trim();
                int code = process.waitFor();
                if (code != 0) {
                    String message = output.isEmpty() ? "请检查 root 授权和 ACC 安装。" : output;
                    if (message.length() > 260) message = message.substring(message.length() - 260);
                    throw new IOException("ACC 调用失败（" + code + "）：" + message);
                }
                return output;
            }
        });
        Thread reader = new Thread(result, "acc-command-output");
        reader.setDaemon(true);
        try {
            process.getOutputStream().close();
            reader.start();
            return result.get(45, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException(TIMEOUT, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw new IOException("ACC 输出读取失败。", e);
        } finally {
            process.destroy();
            result.cancel(true);
        }
    }
}
