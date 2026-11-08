import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("create_bundle", Path(__file__).with_name("create_bundle.py"))
create_bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(create_bundle)


class BundlePathTest(unittest.TestCase):
    def test_private_and_escaping_paths_are_rejected(self):
        for path in ("../escape", "steamapps/common/Game/game.exe", "userdata/123/config.vdf", "config/loginusers.vdf",
                     "steamrtarm64/config/loginusers.vdf", "linuxarm64/steam.pipe"):
            with self.subTest(path=path):
                self.assertFalse(create_bundle.safe_path(path))
        self.assertTrue(create_bundle.safe_path("steamrtarm64/config/steam.vdf"))

    def test_relative_client_symlinks_are_allowed_only_inside_payload(self):
        self.assertTrue(create_bundle.safe_link("linuxarm64/libsteam.so", "libsteam.so.1"))
        self.assertTrue(create_bundle.safe_path("steamrtarm64/config/steam.vdf"))
        self.assertFalse(create_bundle.safe_link("linuxarm64/libsteam.so", "../../steamapps/account.vdf"))
        self.assertFalse(create_bundle.safe_link("linuxarm64/libsteam.so", "/etc/passwd"))


if __name__ == "__main__":
    unittest.main()
