#!/usr/bin/env python3
"""Check both installed editions on a dedicated emulator, optionally saving screenshots."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="emulator-NNNN")
    parser.add_argument("--screenshots", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use a dedicated emulator.")
    if args.screenshots:
        args.screenshots.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.check_output(["adb", "-s", args.serial, *arguments], timeout=120)

    def shell(*arguments):
        return adb("shell", *arguments).decode().strip()

    def wait_for_label(package, expected):
        for _ in range(4):
            output = adb("exec-out", "uiautomator", "dump", "/dev/tty")
            xml = re.search(rb"<hierarchy\b.*?</hierarchy>", output, re.DOTALL)
            if xml:
                for node in ET.fromstring(xml.group()).iter("node"):
                    if node.get("package") != package or node.get("text") not in expected:
                        continue
                    left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
                    if left < right and top < bottom:
                        return
            time.sleep(0.5)
        raise RuntimeError(f"{package}: cannot find a visible home label {expected}")

    keys = ("font_scale", "accelerometer_rotation", "user_rotation")
    original = {key: shell("settings", "get", "system", key) for key in keys}
    packages = {
        "io.local.accpanel": {"仪表盘", "归根", "Rootward"},
        "io.local.rootward.shizuku": {"归根 · Shizuku", "电池概览"},
    }
    try:
        shell("settings", "put", "system", "accelerometer_rotation", "0")
        for label, scale, rotation in (("portrait", "1.0", "0"), ("large-landscape", "2.0", "1")):
            shell("settings", "put", "system", "font_scale", scale)
            shell("settings", "put", "system", "user_rotation", rotation)
            for package, expected in packages.items():
                result = shell("am", "start", "-n", f"{package}/.MainActivity")
                if "Error" in result or "Exception" in result:
                    raise RuntimeError(result)
                wait_for_label(package, expected)
                if args.screenshots:
                    path = args.screenshots / f"{package}-{label}.png"
                    path.write_bytes(adb("exec-out", "screencap", "-p"))
                print(f"{label}: {package} displayed", flush=True)
    finally:
        for key in keys:
            if original[key] == "null":
                shell("settings", "delete", "system", key)
            else:
                shell("settings", "put", "system", key, original[key])


if __name__ == "__main__":
    main()
