package io.local.accpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Exercises production reads and daemon recovery via the isolated shell fixture. */
public final class AccReadbackTest {
    private static final Path fixture = Paths.get(System.getenv("ROOTWARD_FIXTURE"));

    public static void main(String[] args) throws Exception {
        Path iface = fixture.resolve("runtime/.batt-interface.sh");
        Files.write(iface, "battCapacity=gauge/capacity\nbattStatus=gauge/status\ntemp=gauge/temp\n".getBytes(StandardCharsets.UTF_8));
        AccBackend.State first = AccBackend.read();
        require(first.driverLevel == 61 && first.driverTempTenths == 376 && "未充电".equals(first.driverStatus));
        require("已停止".equals(first.daemon) && !Files.exists(fixture.resolve("started")));
        try {
            AccBackend.apply(50, 80);
            throw new AssertionError("Stopped daemon accepted a configuration change");
        } catch (IOException expected) { require(expected.getMessage().contains("未运行")); }
        require(!Files.exists(fixture.resolve("settings-written")));

        Files.write(iface, "battCapacity=gauge/capacity\nbattCapacity=gauge/capacity\n".getBytes(StandardCharsets.UTF_8));
        require(AccBackend.read().driverLevel == null);
        Files.write(iface, "battCapacity=../unexpected/capacity\nbattStatus=/absolute/status\ntemp=/dev/.dummy-temp\n".getBytes(StandardCharsets.UTF_8));
        AccBackend.State invalid = AccBackend.read();
        require(invalid.driverLevel == null && invalid.driverTempTenths == null && invalid.driverStatus == null);
        Files.delete(iface);
        require(AccBackend.read().lower == 50);

        require("运行中".equals(AccBackend.startDaemon().daemon));
        require("运行中".equals(AccBackend.startDaemon().daemon));
        require(Files.readAllLines(fixture.resolve("started")).size() == 1);
        Files.write(fixture.resolve("daemon"), "11\n".getBytes(StandardCharsets.UTF_8));
        try {
            AccBackend.startDaemon();
            throw new AssertionError("Unknown daemon status should not start ACC");
        } catch (IOException expected) { require(expected.getMessage().contains("11")); }
        require(Files.readAllLines(fixture.resolve("started")).size() == 1);
        System.out.println("PASS: ACC control-sensor readback and explicit daemon recovery");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("Unexpected ACC readback");
    }
}
