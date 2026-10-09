import base64
import contextlib
import errno
import json
import io
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen


BINDER_DIR = Path(__file__).resolve().parents[1]
BACKEND_DIR = BINDER_DIR / "backend"
sys.path.insert(0, str(BACKEND_DIR))

# Import against a temporary frozen-app data directory; never open the real binder data.
_IMPORT_DATA = tempfile.TemporaryDirectory()
with patch.dict(os.environ, {"APPDATA": _IMPORT_DATA.name}), \
        patch.object(sys, "frozen", True, create=True), \
        patch.object(sys, "_MEIPASS", _IMPORT_DATA.name, create=True), \
        patch("os.system"):
    import pokemon_binder

import pokemon_server


def card(name, page=1, slot=1, dex=0, rarity="Normal", date="2026-01-01 12:00:00"):
    return {
        "Page": page,
        "Slot": slot,
        "Dex Number": dex,
        "Name": name,
        "Type": rarity,
        "Condition": "NM",
        "Notes": "",
        "Date Added": date,
    }


class TemporaryBinderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.data_dir = Path(self.temp_dir.name) / "data"
        self.data_dir.mkdir()
        self.path_patch = patch.multiple(
            pokemon_binder,
            DATA_DIR=str(self.data_dir),
            CONFIG_FILE=str(self.data_dir / "binder_config.json"),
            DATA_FILE=str(self.data_dir / "pokemon_binder.csv"),
            CACHE_FILE=str(self.data_dir / "pokemon_cache.json"),
            SYNC_STATE_FILE=str(self.data_dir / "sync_state.json"),
        )
        self.path_patch.start()
        pokemon_server.DATA_DIR = str(self.data_dir)
        pokemon_server.STARTUP_LOG = str(self.data_dir / "startup_error.log")
        self.addCleanup(self.path_patch.stop)
        self.addCleanup(self.temp_dir.cleanup)


class BackendDataTests(TemporaryBinderTest):
    def test_csv_add_remove_and_stacks_survive_reload(self):
        self.assertTrue(pokemon_binder.add_card_to_csv(card("pikachu", slot=2, dex=25)))
        self.assertTrue(pokemon_binder.add_card_to_csv(card("pikachu", slot=2, dex=25, rarity="Holofoil Rare")))

        loaded = pokemon_binder.load_collection()
        self.assertEqual(2, len(loaded))
        self.assertEqual(2, len([c for c in loaded if c["Slot"] == 2]))
        loaded.pop(0)
        self.assertTrue(pokemon_binder.save_collection(loaded))
        self.assertEqual("Holofoil Rare", pokemon_binder.load_collection()[0]["Type"])

    def test_dex_sequential_search_and_config(self):
        self.assertEqual((3, 7), pokemon_binder.get_slot_coordinates(25, 3, 3))
        self.assertEqual(25, pokemon_binder.get_abs_index(3, 7, 3, 3))
        collection = [card("pikachu", slot=1), card("eevee", slot=2)]
        self.assertEqual(3, pokemon_binder.find_next_sequential_slot(collection, 3, 3))

        db = {"pikachu": 25, "25": "pikachu", "charizard": 6, "wartortle": 8}
        self.assertEqual((25, "pikachu"), pokemon_binder.find_pokemon(" pika ", db))
        self.assertEqual(("multiple", ["charizard", "wartortle"]), pokemon_binder.find_pokemon("ar", db))

        config = pokemon_binder.DEFAULT_CONFIG.copy()
        config.update({"firebase_user_id": "test-user", "mode": "sequential", "rows": 3, "cols": 3})
        pokemon_binder.save_config(config)
        self.assertEqual("sequential", pokemon_binder.load_config()["mode"])


class BinderApiTests(TemporaryBinderTest):
    def setUp(self):
        super().setUp()
        config = pokemon_binder.DEFAULT_CONFIG.copy()
        config.update({"firebase_user_id": "test-user", "username": "Test Trainer"})
        pokemon_binder.save_config(config)
        self.httpd = pokemon_server.ThreadedHTTPServer(("127.0.0.1", 0), pokemon_server.BinderHTTPRequestHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.base_url = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def stop_server(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def request_json(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(self.base_url + path, data=data, method="GET" if payload is None else "POST")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urlopen(request, timeout=2) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def test_add_remove_and_position_suggestions(self):
        status, _ = self.request_json("/api/add", {"name": "Pikachu", "dex_id": 25, "page": 3, "slot": 7})
        self.assertEqual(200, status)
        self.request_json("/api/add", {"name": "Pikachu", "dex_id": 25, "page": 3, "slot": 7, "type": "Holofoil Rare"})
        self.assertEqual(2, len(pokemon_binder.load_collection()))

        _, suggestion = self.request_json("/api/suggest-position?id=25")
        self.assertEqual((3, 7), (suggestion["page"], suggestion["slot"]))
        self.assertTrue(suggestion["occupied"])

        _, removed = self.request_json("/api/remove", {"page": 3, "slot": 7, "name": "pikachu"})
        self.assertTrue(removed["success"])
        self.assertEqual(1, len(pokemon_binder.load_collection()))

    def test_settings_cover_and_profile_are_saved_locally(self):
        _, result = self.request_json("/api/settings", {
            "rows": 4,
            "cols": 3,
            "mode": "sequential",
            "cover_title": "Test Cover",
            "cover_color": "#123456",
            "username": "Test Trainer",
            "profile_picture_source": "upload",
            "profile_featured_dex": 7,
        })
        self.assertTrue(result["success"])
        self.assertEqual("Test Cover", pokemon_binder.load_config()["cover_title"])
        self.assertEqual(7, pokemon_binder.load_config()["profile_featured_dex"])

        image = base64.b64encode(b"test image bytes").decode()
        _, cover = self.request_json("/api/upload-cover-image", {"image_data": f"data:image/png;base64,{image}"})
        _, profile = self.request_json("/api/upload-profile-image", {"image_data": image})
        self.assertTrue(cover["success"])
        self.assertTrue(profile["success"])
        self.assertEqual(b"test image bytes", (self.data_dir / "cover_image.png").read_bytes())
        self.assertEqual(b"test image bytes", (self.data_dir / "profile_image.png").read_bytes())


class FirebaseSyncTests(TemporaryBinderTest):
    def setUp(self):
        super().setUp()
        self.constants_patch = patch.multiple(pokemon_binder, FIREBASE_API_KEY="", FIREBASE_DB_URL="")
        self.constants_patch.start()
        self.addCleanup(self.constants_patch.stop)
        self.remote = []
        self.fail_get = False
        self.fail_put = False

        def request(url, method="GET", data=None, token=None):
            if url.endswith("/profile.json") or url.endswith("/username.json"):
                return None, None
            if method == "GET":
                return (None, "offline") if self.fail_get else (list(self.remote), None)
            if method == "PUT":
                if self.fail_put:
                    return None, "offline"
                self.remote = list(data)
                return None, None
            return None, "unexpected method"

        self.request_patch = patch.object(pokemon_binder, "make_firebase_request", side_effect=request)
        self.request_patch.start()
        self.addCleanup(self.request_patch.stop)
        self.config = {
            "firebase_enabled": True,
            "firebase_db_url": "https://firebase.test",
            "firebase_auth_method": "none",
            "firebase_user_id": "test-user",
            "username": "Test Trainer",
        }

    def sync(self, local):
        return pokemon_binder.sync_with_firebase(self.config, local)

    def test_first_link_unions_both_sides_and_preserves_duplicate_stacks(self):
        pikachu = card("pikachu", dex=25)
        self.remote = [pikachu, dict(pikachu, **{"Date Added": "later"}), card("eevee", dex=133)]
        local = [pikachu, card("bulbasaur", dex=1)]

        ok, _ = self.sync(local)

        self.assertTrue(ok)
        merged = pokemon_binder.load_collection()
        self.assertEqual({"pikachu", "eevee", "bulbasaur"}, {c["Name"] for c in merged})
        self.assertEqual(2, sum(c["Name"] == "pikachu" for c in merged))

    def test_three_way_merge_keeps_additions_and_removals_do_not_return(self):
        pikachu, charizard = card("pikachu", dex=25), card("charizard", dex=6)
        mew = card("mew", dex=151)
        mew_copy = dict(mew, **{"Date Added": "second copy"})
        base = [pikachu, charizard, mew, mew_copy]
        pokemon_binder.save_sync_state(base)
        local = [pikachu, mew, card("eevee", dex=133)]
        self.remote = [pikachu, charizard, mew, mew_copy, card("snorlax", dex=143)]

        ok, _ = self.sync(local)
        self.assertTrue(ok)
        merged = pokemon_binder.load_collection()
        self.assertEqual({"pikachu", "mew", "eevee", "snorlax"}, {c["Name"] for c in merged})
        self.assertEqual(1, sum(c["Name"] == "mew" for c in merged))

        ok, _ = self.sync(merged)
        self.assertTrue(ok)
        self.assertEqual({"pikachu", "mew", "eevee", "snorlax"}, {c["Name"] for c in self.remote})

    def test_firebase_and_sheets_failures_are_reported_without_cloud_access(self):
        original = [card("pikachu", dex=25)]
        pokemon_binder.save_collection(original)
        self.fail_get = True
        ok, message = self.sync(original)
        self.assertFalse(ok)
        self.assertIn("Failed to retrieve Firebase data", message)
        self.assertEqual(original, pokemon_binder.load_collection())

        self.fail_get = False
        self.fail_put = True
        ok, message = self.sync(original)
        self.assertFalse(ok)
        self.assertIn("Failed to push merged data", message)
        self.assertEqual(original, pokemon_binder.load_collection())

        with patch.object(pokemon_binder, "get_gspread_client", return_value=(None, "offline")):
            ok, message = pokemon_binder.sync_with_google_sheets({"gsheet_enabled": True}, original)
        self.assertFalse(ok)
        self.assertEqual("offline", message)


class ServerLifecycleTests(TemporaryBinderTest):
    def test_server_does_not_allow_reusing_its_listening_port(self):
        self.assertFalse(pokemon_server.ThreadedHTTPServer.allow_reuse_address)

    def test_named_mutex_rejects_an_existing_instance(self):
        kernel32 = type("FakeKernel32", (), {})()
        kernel32.CreateMutexW = Mock(side_effect=(123, 456))
        kernel32.CloseHandle = Mock()

        with patch.object(pokemon_server.os, "name", "nt"), \
                patch("ctypes.WinDLL", return_value=kernel32, create=True), \
                patch("ctypes.set_last_error"), \
                patch("ctypes.get_last_error", side_effect=(0, 183), create=True), \
                patch.object(pokemon_server, "_SINGLE_INSTANCE_HANDLE", None, create=True):
            self.assertTrue(pokemon_server.acquire_single_instance())
            self.assertFalse(pokemon_server.acquire_single_instance())

        kernel32.CreateMutexW.assert_called_with(None, False, "Local\\PokebinderSingleInstance")
        kernel32.CloseHandle.assert_called_once_with(456)

    def test_second_instance_exits_before_starting_server(self):
        with patch.object(pokemon_server, "acquire_single_instance", return_value=False, create=True) as acquire, \
                patch.object(pokemon_server, "ThreadedHTTPServer") as server, \
                patch.object(pokemon_server, "SystemTrayIcon") as tray, \
                patch.object(pokemon_server, "show_startup_message") as show_message, \
                patch.object(pokemon_binder, "load_pokemon_database") as load_database, \
                patch.object(pokemon_binder, "download_default_icon"), \
                patch("threading.Thread"), \
                contextlib.redirect_stdout(io.StringIO()):
            pokemon_server.main()

        acquire.assert_called_once()
        server.assert_not_called()
        tray.assert_not_called()
        show_message.assert_called_once()
        load_database.assert_not_called()

    def test_launch_browser_uses_default_browser_even_when_edge_is_installed(self):
        response = type("Response", (), {"status": 200})()
        connection = type("Connection", (), {
            "request": lambda self, *args: None,
            "getresponse": lambda self: response,
        })()

        with patch("http.client.HTTPConnection", return_value=connection), \
                patch("time.sleep"), \
                patch("shutil.which", return_value=r"C:\\Program Files\\Microsoft\\Edge\\msedge.exe"), \
                patch("subprocess.Popen") as edge_app, \
                patch("webbrowser.open") as default_browser:
            pokemon_server.launch_browser()

        default_browser.assert_called_once_with(f"http://localhost:{pokemon_server.PORT}")
        edge_app.assert_not_called()

    def test_context_menu_uses_the_point_type_declared_for_get_cursor_pos(self):
        import ctypes
        from ctypes import wintypes

        point_type = type("TrayPoint", (ctypes.Structure,), {
            "_fields_": [("x", wintypes.LONG), ("y", wintypes.LONG)]
        })
        received_point_types = []

        class FakeUser32:
            def CreatePopupMenu(self):
                return 1

            def AppendMenuW(self, *args):
                return True

            def SetForegroundWindow(self, *args):
                return True

            def GetCursorPos(self, point):
                received_point_types.append(type(point._obj))
                return True

            def TrackPopupMenu(self, *args):
                return 0

            def DestroyMenu(self, *args):
                return True

            def PostMessageW(self, *args):
                return True

        tray = pokemon_server.SystemTrayIcon(lambda: None, lambda: None)
        tray._point_type = point_type
        tray._user = FakeUser32()
        tray._show_menu(1)

        self.assertEqual([point_type], received_point_types)

    def test_main_keeps_server_without_a_heartbeat_monitor(self):
        started_targets = []

        class RecordingThread:
            def __init__(self, target, **kwargs):
                self.target = target

            def start(self):
                started_targets.append(self.target.__name__)

        class FakeServer:
            def __init__(self):
                self.served = False
                self.closed = False

            def serve_forever(self):
                self.served = True

            def server_close(self):
                self.closed = True

        fake_server = FakeServer()
        fake_tray = type("FakeTray", (), {"start": lambda self: None, "stop": lambda self: None})()
        with patch.object(pokemon_server, "acquire_single_instance", return_value=True), \
                patch.object(pokemon_binder, "load_pokemon_database", return_value={}), \
                patch.object(pokemon_binder, "download_default_icon"), \
                patch.object(pokemon_server, "ThreadedHTTPServer", return_value=fake_server), \
                patch.object(pokemon_server, "SystemTrayIcon", return_value=fake_tray, create=True) as tray, \
                patch("threading.Thread", RecordingThread), \
                contextlib.redirect_stdout(io.StringIO()):
            pokemon_server.main()

        self.assertTrue(fake_server.served)
        self.assertTrue(fake_server.closed)
        tray.assert_called_once()
        self.assertNotIn("monitor_heartbeat", started_targets)

    def test_port_conflict_does_not_start_a_browser_or_leave_a_thread(self):
        started_targets = []

        class RecordingThread:
            def __init__(self, target, **kwargs):
                self.target = target

            def start(self):
                started_targets.append(self.target.__name__)

        with patch.object(pokemon_server, "acquire_single_instance", return_value=True), \
                patch.object(pokemon_binder, "load_pokemon_database", return_value={}), \
                patch.object(pokemon_binder, "download_default_icon"), \
                patch.object(pokemon_server, "ThreadedHTTPServer", side_effect=OSError(errno.EADDRINUSE, "in use")), \
                patch.object(pokemon_server, "show_startup_message", create=True) as show_message, \
                patch("threading.Thread", RecordingThread), \
                patch("builtins.open", unittest.mock.mock_open()), \
                contextlib.redirect_stdout(io.StringIO()):
            try:
                pokemon_server.main()
            except SystemExit:
                pass

        self.assertEqual([], started_targets)
        show_message.assert_called_once()


if __name__ == "__main__":
    unittest.main()
