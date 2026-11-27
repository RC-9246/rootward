package io.local.accpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Installs only the reviewed, bundled plugin on the matching ACC daemon. */
final class StrictHold {
    static final String VERSION = "v2023.10.16 (202310160)";
    static final String DIGEST = "a5302de0559e9dc101341bec25c40f75f3294112691f06076525b21b9b988f07";
    private static final String LEGACY_DIGEST = "7486deb91b195ae41d9b07396b0a56d2ae98bcaaaf2f2317074ba18fca5a1aaa";
    private static final String V14_DIGEST = "9377b0adb9c5a7162b7236c90fb41b93c57188ad07b8ef28f89dd86befbe65f1";
    private static final String OWNER = "io.local.accpanel/cable-hold/v1";
    private static final String ENV =
            "base=/data/adb/vr25/acc-data\ndir=$base/rootward-hold\nplugins=$base/plugins\n"
            + "plugin=$plugins/rootward-hold.sh\nlock=/dev/.vr25/acc/acc.lock\n"
            + "has() { [ -e \"$1\" ] || [ -L \"$1\" ]; }\n"
            + "reg() { [ ! -L \"$1\" ] && [ -f \"$1\" ] && [ \"$(stat -c %u \"$1\")\" = 0 ]; }\n"
            + "secure_parent() { [ ! -L \"$1\" ] && [ -d \"$1\" ] || return 1\n"
            + "  case \"$(stat -c %u:%a \"$1\")\" in 0:700|0:750|0:755) return 0;; *) return 1;; esac; }\n"
            + "private_dir() { [ ! -L \"$dir\" ] && [ -d \"$dir\" ] && [ \"$(stat -c %u:%g:%a \"$dir\")\" = 0:0:700 ]"
            + " && reg \"$dir/owner\" && [ \"$(cat \"$dir/owner\")\" = '" + OWNER + "' ]; }\n"
            + "matches_plugin() { reg \"$plugin\" && [ \"$(stat -c %a \"$plugin\")\" = 600 ]"
            + " && [ \"$(sha256sum \"$plugin\" | cut -d' ' -f1)\" = \"$1\" ]; }\n"
            + "valid_plugin() { matches_plugin '" + DIGEST + "'; }\n"
            + "v14_plugin() { matches_plugin '" + V14_DIGEST + "'; }\n"
            + "legacy_plugin() { v14_plugin || matches_plugin '" + LEGACY_DIGEST + "'; }\n"
            + "loaded() { local stamp=\"$dir/${1:-loaded-v3-pid}\"; reg \"$stamp\" && [ \"$(stat -c %a \"$stamp\")\" = 600 ] || return 1\n"
            + "  reg \"$lock\" && read -r daemon_pid < \"$lock\" || return 1\n"
            + "  case \"$daemon_pid\" in ''|0|*[!0-9]*) return 1;; esac\n"
            + "  [ \"$(cat \"$stamp\")\" = \"$daemon_pid\" ] && kill -0 \"$daemon_pid\" 2>/dev/null"
            + " && tr '\\000' '\\n' < /proc/\"$daemon_pid\"/cmdline | grep -Fxq /data/adb/vr25/acc/accd.sh; }\n";

    // Avoid printing phase/health into the raw ACC settings portion of the response.
    static final String SNAPSHOT = ENV
            + "hold_setting=unsupported; hold_phase=false; hold_loaded=false; manual_resume=false; scheduled_at=0\n"
            + "if private_dir; then\n"
            + "  if reg \"$dir/enabled\"; then hold_setting=true; reg \"$dir/holding\" && hold_phase=true\n"
            + "  elif ! has \"$dir/enabled\" && { valid_plugin || legacy_plugin || ! has \"$plugin\"; }; then hold_setting=false; fi\n"
            + "  if $hold_phase && { valid_plugin || v14_plugin; } && reg \"$dir/resume-once\"; then manual_resume=true; fi\n"
            + "  if valid_plugin && reg \"$dir/enabled\"; then for plan in \"$dir\"/plan-*; do reg \"$plan\" || continue; tail=${plan##*/plan-}; scheduled_at=${tail%%-*}; break; done; fi\n"
            + "  if [ \"$daemon_code\" = 0 ]; then\n"
            + "    if valid_plugin && loaded; then\n"
            + "      hold_loaded=true\n"
            + "    elif v14_plugin && loaded loaded-v2-pid; then hold_loaded=true;\n"
            + "    elif ! v14_plugin && legacy_plugin && loaded loaded-pid; then hold_loaded=true; fi\n"
            + "  fi\n"
            + "elif secure_parent \"$base\" && secure_parent \"$plugins\" && ! has \"$dir\" && ! has \"$plugin\"; then hold_setting=false; fi\n"
            + "printf '\\n@hold_setting=%s\\n@hold_phase=%s\\n@hold_loaded=%s\\n@manual_resume=%s\\n' \"$hold_setting\" \"$hold_phase\" \"$hold_loaded\" \"$manual_resume\"\nprintf '@scheduled_at=%s\\n' \"$scheduled_at\"\n";

    private static final String INSTALL =
            "fail() { echo \"$1\"; exit 83; }\numask 077\n"
            + "secure_parent \"$base\" || fail 'ACC 数据目录不可用或权限异常。'\n"
            + "if has \"$dir\"; then private_dir || fail '同名控制目录归属未知。'; fi\n";
    private static final String ENABLE =
            "secure_parent \"$plugins\" || fail 'ACC 插件目录不可用或权限异常。'\n"
            + "if has \"$plugin\" && ! valid_plugin && ! legacy_plugin; then fail '同名 ACC 插件无法核对归属。'; fi\n"
            + "[ \"$(\"$acc\" -v | sed '/^$/d')\" = '" + VERSION + "' ] && \"$acc\" -D >/dev/null 2>&1"
            + " || fail '需要已运行的 ACC " + VERSION + "。'\n"
            + "settings=$(\"$acc\" -sp '^(resume_capacity|pause_capacity|resume_temp)=') || fail '无法读取当前阈值。'\n"
            + "lo=$(echo \"$settings\" | sed -n 's/^resume_capacity=//p'); hi=$(echo \"$settings\" | sed -n 's/^pause_capacity=//p')\n"
            + "rt=$(echo \"$settings\" | sed -n 's/^resume_temp=//p'); case \"$rt\" in ''|*[!0-9]*) fail '恢复温度配置与严格回落不兼容。';; esac\n"
            + "[ \"$lo\" -ge 0 ] && [ \"$hi\" -le 100 ] && [ \"$lo\" -lt \"$hi\" ] || fail '需要有效的百分比充电区间。'\n"
            + "stage=$(mktemp -d \"$base/.rootward-install.XXXXXX\") || fail '无法准备安装目录。'\n"
            + "created_plugin=false; migrated=false; newly_enabled=false; restarted=false\n"
            + "cleanup() { result=$?; trap - EXIT; if [ \"$result\" != 0 ]; then\n"
            + "  if $newly_enabled && private_dir; then rm -f \"$dir/enabled\" \"$dir/holding\" \"$dir/resume-once\" \"$dir\"/plan-*; fi\n"
            + "  if $created_plugin && valid_plugin; then rm -f \"$plugin\"; fi\n"
            + "  if $migrated && valid_plugin && [ -f \"$stage/previous\" ]; then\n"
            + "    mv -f \"$stage/previous\" \"$plugin\" && \"$acc\" -D restart </dev/null >/dev/null 2>&1 || :; fi\n"
            + "  if $restarted && ! \"$acc\" -D >/dev/null 2>&1; then \"$acc\" -D start </dev/null >/dev/null 2>&1; fi\n"
            + "fi; rm -rf \"$stage\"; exit \"$result\"; }; trap cleanup EXIT\n"
            + "if ! has \"$dir\"; then mkdir -m 700 \"$stage/state\" && chown 0:0 \"$stage/state\" && echo '" + OWNER + "' > \"$stage/state/owner\""
            + " && mv -Tn \"$stage/state\" \"$dir\" || fail '无法创建归根控制目录。'; fi\n"
            + "private_dir || fail '归根控制目录校验失败。'\n"
            + "for item in enabled holding resume-once loaded-pid loaded-v2-pid loaded-v3-pid; do if has \"$dir/$item\" && ! reg \"$dir/$item\"; then fail '状态文件归属异常。'; fi; done\n"
            + "if ! valid_plugin; then\n"
            + "  printf %s \"$body64\" | base64 -d > \"$stage/plugin\" && chmod 600 \"$stage/plugin\" || fail '准备插件失败。'\n"
            + "  [ \"$(sha256sum \"$stage/plugin\" | cut -d' ' -f1)\" = '" + DIGEST + "' ] && /system/bin/sh -n \"$stage/plugin\" || fail '插件校验失败。'\n"
            + "  if legacy_plugin; then\n"
            + "    cp -p \"$plugin\" \"$stage/previous\" && cmp -s \"$stage/previous\" \"$plugin\" && legacy_plugin && mv -f \"$stage/plugin\" \"$plugin\" || fail '升级旧版插件失败。'\n"
            + "    migrated=true\n"
            + "  elif ! has \"$plugin\"; then\n"
            + "    ln \"$stage/plugin\" \"$plugin\" || fail '安装插件失败。'; created_plugin=true\n"
            + "  else fail '同名插件内容已更改。'; fi\n"
            + "fi\n"
            + "valid_plugin || fail '归根插件校验失败。'\n"
            + "if ! reg \"$dir/enabled\"; then\n"
            + "  node=$(sed -n 's/^battCapacity=//p' /dev/.vr25/acc/.batt-interface.sh); soc=\n"
            + "  case \"$node\" in ''|/*|*..*|*[!a-zA-Z0-9_./-]*) ;; *) soc=$(cat \"/sys/class/power_supply/$node\" 2>/dev/null);; esac\n"
            + "  case \"$soc\" in ''|*[!0-9]*) fail '无法获取 ACC 电量。';; esac\n"
            + "  [ \"$soc\" -le 100 ] || fail 'ACC 电量无效。'\n"
            + "  newly_enabled=true\n"
            + "  if [ \"$soc\" -ge \"$hi\" ] || { $seed && [ \"$soc\" -gt \"$lo\" ]; }; then"
            + " touch \"$dir/holding\" || fail '初始化保持状态失败。'; else rm -f \"$dir/holding\" || fail '清除旧状态失败。'; fi\n"
            + "  touch \"$dir/enabled\" || fail '无法启用保持。'; fi\n"
            + "if $migrated || ! loaded; then restarted=true; \"$acc\" -D restart </dev/null >/dev/null 2>&1 || fail 'ACC 重启失败。'\n"
            + "  tries=0; until loaded && \"$acc\" -D >/dev/null 2>&1; do tries=$((tries + 1));"
            + " [ \"$tries\" -lt 18 ] || fail 'ACC 未确认载入插件。'; sleep 1; done; fi\n"
            + "trap - EXIT; rm -rf \"$stage\"\n";

    static String command(boolean enable, String body, boolean seed) throws Exception {
        return command(enable, body, seed, false);
    }

    private static String command(boolean enable, String body, boolean seed, boolean requireHold) throws Exception {
        if (!enable) return ENV + INSTALL
                + "if has \"$dir\"; then rm -f \"$dir/enabled\" \"$dir/holding\" \"$dir/resume-once\" \"$dir\"/plan-* || fail '停用保持失败。'; fi\n";
        if (body == null || body.length() > 30000) throw new IOException("归根插件内容异常。");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            digest.append(Character.forDigit((b & 0xff) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        }
        if (!DIGEST.contentEquals(digest)) throw new IOException("归根插件内容校验失败。");
        String precondition = requireHold
                ? "private_dir && reg \"$dir/enabled\" && reg \"$dir/holding\" || fail '请先开启严格巡航并等待进入回落阶段。'\n"
                : "";
        return ENV + INSTALL + precondition + "body64='" + Base64.getEncoder().encodeToString(bytes) + "'\nseed=" + seed + "\n" + ENABLE;
    }

    /** The marker records the approved ceiling; ACC owns every charger transition. */
    static String manualCommand(boolean enabled, String pluginBody) throws Exception {
        if (!enabled) return ENV + INSTALL
            + "private_dir || fail '未配置严格巡航。'\n"
            + "rm -f \"$dir/resume-once\" || fail '取消单次继续充电失败。'\n";
        return command(true, pluginBody, false, true)
            + "private_dir && valid_plugin && loaded && reg \"$dir/enabled\" && reg \"$dir/holding\" || fail '需要正在等待回落的严格巡航。'\n"
            + "node=$(sed -n 's/^battCapacity=//p' /dev/.vr25/acc/.batt-interface.sh)\n"
            + "case \"$node\" in ''|/*|*..*|*[!a-zA-Z0-9_./-]*) fail '无法获取 ACC 电量。';; esac\n"
            + "soc=$(cat \"/sys/class/power_supply/$node\") || fail '无法获取 ACC 电量。'\n"
            + "case \"$soc\" in ''|*[!0-9]*) fail '电量读数无效。';; esac\n"
            + "[ \"$soc\" -gt \"$lo\" ] && [ \"$soc\" -lt \"$hi\" ] || fail '电量须在恢复线和停充上限之间。'\n"
            + "mark=$(mktemp \"$dir/.resume-once.XXXXXX\") || fail '无法准备单次请求。'\n"
            + "trap 'rm -f \"$mark\"' EXIT\n"
            + "printf '%s\\n' \"$hi\" > \"$mark\" && chmod 600 \"$mark\" && mv -f \"$mark\" \"$dir/resume-once\" || fail '保存单次请求失败。'\n"
            + "trap - EXIT; rm -f \"$dir\"/plan-*\n";
    }

    static String scheduleCommand(long seconds, String pluginBody) throws Exception {
        String clear = "rm -f \"$dir\"/plan-* || fail '取消预约失败。'\n";
        if (seconds == 0) return ENV + INSTALL + "private_dir || fail '未配置巡航。'\n" + clear;
        if (seconds < 1_000_000_000L || seconds > 9_999_999_999L) throw new IOException("预约时间无效。");
        return command(true, pluginBody, false, true)
            + "now=$(date +%s); at=" + seconds + "\n"
            + "[ \"$at\" -gt \"$now\" ] && [ \"$((at - now))\" -le 90000 ] || fail '请选择未来一天内的时间。'\n"
            + "reg \"$dir/enabled\" && reg \"$dir/holding\" && ! has \"$dir/resume-once\" || fail '请在等待回落且未手动补电时预约。'\n"
            + "set -- \"$dir\"/plan-*; ! has \"$1\" || fail '请先取消已有预约。'\n"
            + "mktemp \"$dir/plan-$at-$lo-$hi.XXXXXX\" >/dev/null || fail '无法保存预约。'\n";
    }

    private StrictHold() {}
}
