import argparse
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


FORBIDDEN_MARKERS = (b"ENC_GEMINI_API_KEY", b"PLACEHOLDER_ENC_GEMINI_API_KEY")
APK_TO_CHECK = None


def resolve_apk_path(apk_argument=None):
    return Path(apk_argument) if apk_argument else Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk"


def find_gemini_key_markers(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        dex_files = (name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex"))
        return {
            marker.decode("ascii")
            for name in dex_files
            for marker in FORBIDDEN_MARKERS
            if marker in apk.read(name)
        }


class GeminiApkTest(unittest.TestCase):
    def test_checker_detects_a_fake_legacy_key_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "fake.apk"
            with zipfile.ZipFile(apk_path, "w") as apk:
                apk.writestr("classes.dex", b"PLACEHOLDER_ENC_GEMINI_API_KEY")

            self.assertEqual(find_gemini_key_markers(apk_path), {"ENC_GEMINI_API_KEY", "PLACEHOLDER_ENC_GEMINI_API_KEY"})

    def test_explicit_apk_path_is_selected(self):
        self.assertEqual(resolve_apk_path("beta-release.apk"), Path("beta-release.apk"))

    def test_selected_apk_does_not_contain_legacy_gemini_key_config(self):
        apk_path = APK_TO_CHECK or resolve_apk_path()
        self.assertTrue(apk_path.exists(), f"APK not found: {apk_path}")
        self.assertEqual(find_gemini_key_markers(apk_path), set())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, help="APK to check instead of the debug build")
    args, unittest_args = parser.parse_known_args()
    APK_TO_CHECK = args.apk
    unittest.main(argv=[sys.argv[0], *unittest_args])
