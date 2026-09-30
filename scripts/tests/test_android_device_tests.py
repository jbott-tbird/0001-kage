import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "android_device_tests", Path(__file__).parents[1] / "android_device_tests.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DeviceTargetTest(unittest.TestCase):
    def test_missing_serial_and_physical_device_are_rejected_without_adb(self):
        with patch.object(runner, "output") as adb:
            for serial in (None, "", "physical-device", "emulator-5554,emulator-5560"):
                with self.assertRaises(ValueError):
                    runner.verify_target("adb", serial)
            adb.assert_not_called()

    def test_manual_google_emulator_is_rejected(self):
        with patch.object(runner, "output", side_effect=["device", "Kage_Google_API37\nOK"]):
            with self.assertRaises(ValueError):
                runner.verify_target("adb", "emulator-5554")

    def test_target_is_selected_by_name_not_port(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(runner, "output", side_effect=[
            "List of devices attached\nemulator-5554 device\nemulator-5562 device",
            "device", "Kage_Google_API37\nOK",
            "device", runner.TEST_AVD + "\nOK",
        ]):
            self.assertEqual("emulator-5562", runner.select_target("adb"))

    def test_explicit_wrong_target_does_not_fall_back_to_another_device(self):
        with patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5554"}), patch.object(
            runner, "output", side_effect=["device", "Kage_Google_API37\nOK"]
        ):
            with self.assertRaises(ValueError):
                runner.select_target("adb")

    def test_no_test_emulator_fails_closed(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(
            runner, "output", return_value="List of devices attached"
        ):
            with self.assertRaises(ValueError):
                runner.select_target("adb")


if __name__ == "__main__":
    unittest.main()
