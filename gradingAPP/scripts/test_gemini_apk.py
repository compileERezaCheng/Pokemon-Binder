import tempfile
import unittest
import zipfile
from pathlib import Path


FORBIDDEN_MARKERS = (b"ENC_GEMINI_API_KEY", b"PLACEHOLDER_ENC_GEMINI_API_KEY")


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

    def test_debug_apk_does_not_contain_legacy_gemini_key_config(self):
        apk_path = Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk"
        self.assertTrue(apk_path.exists(), f"Build the debug APK first: {apk_path}")
        self.assertEqual(find_gemini_key_markers(apk_path), set())


if __name__ == "__main__":
    unittest.main()
