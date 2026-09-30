#!/usr/bin/env python3
"""Run Kage instrumentation only on its dedicated test AVD."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TEST_AVD = "Kage_Isolated_Tests_API30"


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    properties = ROOT / "Android/local.properties"
    if not sdk and properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1]
                break
    if sdk:
        return str(Path(sdk) / "platform-tools/adb")
    return shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")


def output(adb, *args):
    return subprocess.check_output([adb, *args], text=True, timeout=15).strip()


def verify_target(adb, serial):
    if not serial or not serial.startswith("emulator-") or "," in serial:
        raise ValueError("ANDROID_SERIAL must select one dedicated test emulator.")
    if output(adb, "-s", serial, "get-state") != "device":
        raise ValueError(f"{serial} is not ready.")
    name = output(adb, "-s", serial, "emu", "avd", "name").splitlines()[0]
    if name != TEST_AVD:
        raise ValueError(f"Refusing tests on {name!r}; required AVD is {TEST_AVD}.")
    return serial


def select_target(adb):
    requested = os.environ.get("ANDROID_SERIAL")
    if requested:
        return verify_target(adb, requested)
    candidates = []
    for line in output(adb, "devices").splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device" and fields[0].startswith("emulator-"):
            try:
                candidates.append(verify_target(adb, fields[0]))
            except ValueError:
                continue
    if len(candidates) != 1:
        raise ValueError(f"Start exactly one {TEST_AVD} emulator in Device Manager.")
    return candidates[0]


def main():
    adb = adb_path()
    try:
        if sys.argv[1:] == ["--check"]:
            serial = verify_target(adb, os.environ.get("ANDROID_SERIAL"))
            print(f"Verified test emulator: {TEST_AVD} ({serial})")
            return 0
        serial = select_target(adb)
        env = dict(os.environ, ANDROID_SERIAL=serial)
        print(f"Running tests on {TEST_AVD} ({serial})", flush=True)
        return subprocess.call(
            [str(ROOT / "Android/gradlew"), ":app:connectedDebugAndroidTest", *sys.argv[1:]],
            cwd=ROOT / "Android", env=env,
        )
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Device tests blocked: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
