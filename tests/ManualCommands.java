package io.local.accpanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Generate the real installer commands for tests in an isolated toybox directory. */
public class ManualCommands {
    public static void main(String[] args) throws Exception {
        String plugin = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Path target = Paths.get(args[1]);
        save(target.resolve("resume"), StrictHold.manualCommand(true, plugin));
        save(target.resolve("cancel"), StrictHold.manualCommand(false, null));
        save(target.resolve("schedule"), StrictHold.scheduleCommand(System.currentTimeMillis()/1000 + 1800, plugin));
        save(target.resolve("cancelSchedule"), StrictHold.scheduleCommand(0, null));
        save(target.resolve("snapshot"), StrictHold.SNAPSHOT);
    }
    private static void save(Path target, String s) throws Exception {
        s = s.replace("/data/adb/vr25/acc-data", "$fixture/data")
                .replace("/dev/.vr25/acc/", "$fixture/runtime/")
                .replace("/sys/class/power_supply/", "$fixture/hardware/")
                .replace("/proc/\"$daemon_pid\"/cmdline", "\"$fixture/cmdline\"");
        Files.write(target, s.getBytes(StandardCharsets.UTF_8));
    }
}
