#!/usr/bin/env python3
"""Exercise the real automation provider on a dedicated emulator configured with 50/75 limits."""
import argparse
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="emulator-NNNN")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use a dedicated emulator.")

    def adb(*arguments):
        return subprocess.check_output(
            ["adb", "-s", args.serial, *arguments], timeout=30, text=True
        ).strip()

    uri = "content://io.local.rootward.shizuku.automation/v1/state"

    def observe():
        return adb("shell", "content", "query", "--uri", uri)

    def expect(request, level=None, phase=None):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = observe()
            fields = dict(re.findall(r"(\w+)=([^,\s]+)", result))
            if (fields.get("request") == request
                    and (level is None or fields.get("level") == str(level))
                    and (phase is None or fields.get("phase") == phase)):
                return
            time.sleep(0.4)
        raise RuntimeError(f"Expected {request}, {level}, {phase}. Last observation: {result}")

    def power(key, value):
        result = adb("emu", "power", key, value)
        if "OK" not in result:
            raise RuntimeError(result)

    for _ in range(15):
        if "lower=50, upper=75" in observe():
            break
        time.sleep(1)
    else:
        parser.error("Start the recorder at 50/75 and select com.android.shell as its automation recipient.")
    original = dict(line.split(": ", 1) for line in adb("emu", "power", "display").splitlines() if ": " in line)
    try:
        power("present", "true")
        power("health", "good")
        power("capacity", "75")
        expect("pause", 75, "waiting")
        power("capacity", "62")
        for charger in ("off", "on"):
            power("ac", charger)
            expect("pause", 62, "waiting")
        power("health", "overvoltage")
        power("capacity", "50")
        expect("pause", 50, "filling")
        power("health", "unknown")
        expect("pause", 50, "filling")
        for fault in ("overheat", "dead", "failure"):
            power("health", "good")
            expect("allow", 50)
            power("health", fault)
            expect("pause", 50)
        power("health", "good")
        expect("allow", 50)
        power("present", "false")
        expect("unknown")
        power("present", "true")
        expect("allow", 50)
        print("Cable changes, explicit faults, missing health, recovery and absent battery passed.")
    finally:
        power("capacity", original["capacity"])
        power("health", original["health"].lower())
        power("ac", "on" if original["AC"] == "online" else "off")
        power("present", original["present"].lower())


if __name__ == "__main__":
    main()
