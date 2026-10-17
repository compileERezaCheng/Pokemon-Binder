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
import struct
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
    @staticmethod
    def species_pages(old_cache):
        names = {value["id"]: name for name, value in old_cache.items() if not name.isdigit() and isinstance(value, dict)}
        results = [
            {"name": names.get(dex, "pecharunt" if dex == 1025 else f"species-{dex}"),
             "url": f"https://pokeapi.co/api/v2/pokemon-species/{dex}/"}
            for dex in range(1, 1026)
        ]
        return [
            {"count": 1025, "next": "https://pokeapi.co/api/v2/pokemon-species/?offset=1000&limit=1000", "results": results[:1000]},
            {"count": 1025, "next": None, "results": results[1000:]},
        ]

    def write_cache(self, cache):
        with open(pokemon_binder.CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(cache, f)

    def test_old_gen1_cache_is_paginated_and_upgraded_without_losing_types(self):
        old_cache = pokemon_binder.get_gen1_fallback()
        self.write_cache(old_cache)
        pages = [io.BytesIO(json.dumps(page).encode()) for page in self.species_pages(old_cache)]

        with patch.object(pokemon_binder.urllib.request, "urlopen", side_effect=pages) as request:
            database = pokemon_binder.load_pokemon_database()

        self.assertEqual(2, request.call_count)
        self.assertGreaterEqual(sum(not key.isdigit() for key in database), 1025)
        self.assertEqual("grass", database["bulbasaur"]["type"])
        self.assertNotIn("type", database["pecharunt"])
        self.assertEqual("pecharunt", database["1025"]["name"])
        self.assertEqual((1025, "pecharunt"), pokemon_binder.find_pokemon("pecharunt", database))
        self.assertEqual((1025, "pecharunt"), pokemon_binder.find_pokemon("1025", database))

    def test_bundled_gen1_cache_is_upgraded_for_a_fresh_desktop_profile(self):
        bundled_cache = Path(self.temp_dir.name) / "bundle" / "data" / "pokemon_cache.json"
        bundled_cache.parent.mkdir(parents=True)
        old_cache = pokemon_binder.get_gen1_fallback()
        bundled_cache.write_text(json.dumps(old_cache), encoding="utf-8")
        original_bytes = bundled_cache.read_bytes()
        pages = [io.BytesIO(json.dumps(page).encode()) for page in self.species_pages(old_cache)]

        with patch.object(pokemon_binder, "get_resource_path", return_value=str(bundled_cache)), \
                patch.object(pokemon_binder.urllib.request, "urlopen", side_effect=pages):
            database = pokemon_binder.load_pokemon_database()

        self.assertGreaterEqual(sum(not key.isdigit() for key in database), 1025)
        self.assertTrue(Path(pokemon_binder.CACHE_FILE).is_file())
        self.assertEqual(original_bytes, bundled_cache.read_bytes())

    def test_failed_pokemon_cache_refresh_keeps_old_file_and_retries(self):
        old_cache = pokemon_binder.get_gen1_fallback()
        self.write_cache(old_cache)
        original_bytes = Path(pokemon_binder.CACHE_FILE).read_bytes()
        first_page = self.species_pages(old_cache)[0]
        responses = [io.BytesIO(json.dumps(first_page).encode()), OSError("offline"), OSError("offline")]

        with patch.object(pokemon_binder.urllib.request, "urlopen", side_effect=responses) as request:
            self.assertEqual(old_cache, pokemon_binder.load_pokemon_database())
            self.assertEqual(old_cache, pokemon_binder.load_pokemon_database())

        request.assert_called()
        self.assertEqual(3, request.call_count)
        self.assertEqual(original_bytes, Path(pokemon_binder.CACHE_FILE).read_bytes())

    def test_partial_species_list_does_not_replace_previous_cache(self):
        old_cache = pokemon_binder.get_gen1_fallback()
        self.write_cache(old_cache)
        original_bytes = Path(pokemon_binder.CACHE_FILE).read_bytes()
        partial = {"count": 1025, "next": None, "results": [
            {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon-species/1/"}
        ]}

        with patch.object(pokemon_binder.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(partial).encode())):
            database = pokemon_binder.load_pokemon_database()

        self.assertEqual(old_cache, database)
        self.assertEqual(original_bytes, Path(pokemon_binder.CACHE_FILE).read_bytes())

    def test_legacy_flat_cache_remains_searchable_by_name_and_number(self):
        old_cache = pokemon_binder.get_gen1_fallback()
        old_names = {value["id"]: name for name, value in old_cache.items() if not name.isdigit()}
        names = {dex: old_names.get(dex, "pecharunt" if dex == 1025 else f"species-{dex}") for dex in range(1, 1026)}
        flat_cache = {name: dex for dex, name in names.items()}
        flat_cache.update({str(dex): name for dex, name in names.items()})
        self.write_cache(flat_cache)

        with patch.object(pokemon_binder.urllib.request, "urlopen") as request:
            database = pokemon_binder.load_pokemon_database()

        request.assert_not_called()
        self.assertEqual((1025, "pecharunt"), pokemon_binder.find_pokemon("pecharunt", database))
        self.assertEqual((1025, "pecharunt"), pokemon_binder.find_pokemon("1025", database))

    def test_card_image_url_survives_csv_reload(self):
        image_url = "https://assets.tcgdex.net/en/base/base1/4"
        self.assertTrue(pokemon_binder.add_card_to_csv({**card("charizard", dex=6), "Image URL": image_url}))

        self.assertEqual(image_url, pokemon_binder.load_collection()[0].get("Image URL"))

    def test_scrydex_match_requires_same_set_number_language_and_variant(self):
        tcgdex = {
            "id": "me1-084", "name": "M Charizard-EX", "localId": "084",
            "stage": "MEGA", "suffix": "EX",
            "set": {"id": "me1", "name": "Mega Evolution"},
        }
        correct = {
            "id": "scrydex-card-84", "name": "M Charizard-EX", "number": "84",
            "printed_number": "084/132", "language_code": "EN", "subtypes": ["MEGA"],
            "expansion": {"id": "different-id", "name": "Mega Evolution"},
            "images": [{"type": "front", "large": "https://images.scrydex.com/pokemon/scrydex-card-84/large"}],
        }
        wrong_set = {**correct, "expansion": {"id": "me1", "name": "Another set"}}
        wrong_number = {**correct, "number": "85", "printed_number": "085/132"}
        wrong_language = {**correct, "language_code": "JA"}
        wrong_variant = {**correct, "name": "Charizard ex", "subtypes": []}

        match = pokemon_server.match_scrydex_card(
            tcgdex, {"data": [wrong_set, wrong_number, wrong_language, wrong_variant, correct], "totalCount": 5}
        )

        self.assertEqual({"card_id": "scrydex-card-84", "image_url": correct["images"][0]["large"]}, match)
        self.assertIsNone(pokemon_server.match_scrydex_card(
            tcgdex, {"data": [correct, {**correct, "id": "duplicate"}], "totalCount": 2}
        ))

    def test_catalog_and_grade_metadata_survive_csv_reload(self):
        details = json.dumps({"assessable": True, "criteria": {"centering": {"score": 8, "evidence": "even"}}})
        metadata = {
            "Catalog Card ID": "base1-4", "Set ID": "base1", "Set Name": "Base",
            "Card Number": "4", "Catalog Rarity": "Rare Holo",
            "Image URL": "https://assets.tcgdex.net/en/base/base1/4/low.webp",
            "Grade": "8.5", "Grade Details": details,
        }
        self.assertTrue(pokemon_binder.add_card_to_csv({**card("charizard", dex=6), **metadata}))
        loaded = pokemon_binder.load_collection()[0]
        for key, value in metadata.items():
            self.assertEqual(value, loaded.get(key), key)

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
        self.server_data_patch = patch.object(pokemon_server, "DATA_DIR", str(self.data_dir))
        self.server_data_patch.start()
        self.addCleanup(self.server_data_patch.stop)
        original_port = pokemon_server.PORT
        self.addCleanup(setattr, pokemon_server, "PORT", original_port)
        self.httpd = pokemon_server.ThreadedHTTPServer(("127.0.0.1", 0), pokemon_server.BinderHTTPRequestHandler)
        pokemon_server.PORT = self.httpd.server_address[1]
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

    def test_catalog_metadata_update_preserves_copy_data(self):
        details = json.dumps({"assessable": True, "criteria": {"centering": {"score": 8, "evidence": "even"}}})
        _, added = self.request_json("/api/add", {
            "name": "Pikachu", "dex_id": 25, "page": 3, "slot": 7, "type": "Rare Holo",
            "condition": "LP", "notes": "keep my note", "catalog_card_id": "base1-25",
            "set_id": "base1", "set_name": "Base", "card_number": "25",
            "catalog_rarity": "Rare Holo", "image_url": "https://assets.tcgdex.net/en/base/base1/25/low.webp",
            "grade": 8.5, "grade_details": details,
        })
        self.assertTrue(added["success"])
        before = pokemon_binder.load_collection()[0]
        self.assertTrue(before.get("Copy ID"))
        self.assertEqual(("Rare Holo", "Rare Holo"), (before["Type"], before["Catalog Rarity"]))
        status, updated = self.request_json("/api/update-card", {
            "copy_id": before["Copy ID"], "page": before["Page"], "slot": before["Slot"], "name": before["Name"],
            "date_added": before["Date Added"], "catalog_card_id": "neo3-12",
            "set_id": "neo3", "set_name": "Neo Revelation", "card_number": "12",
            "catalog_rarity": "Rare", "image_url": "https://images.scrydex.com/pokemon/scrydex-card-12/large",
            "image_source": "Scrydex", "image_match_id": "scrydex-card-12",
        })
        self.assertEqual(200, status, updated)
        after = pokemon_binder.load_collection()[0]
        self.assertEqual(("neo3-12", "neo3", "12"), (after["Catalog Card ID"], after["Set ID"], after["Card Number"]))
        self.assertEqual(("Scrydex", "scrydex-card-12"), (after["Image Source"], after["Image Match ID"]))
        self.assertEqual("https://images.scrydex.com/pokemon/scrydex-card-12/large", after["Image URL"])
        self.assertEqual(before["Type"], after["Type"])
        for key in ("Page", "Slot", "Date Added", "Notes", "Grade", "Grade Details"):
            self.assertEqual(before[key], after[key], key)

    def test_notes_update_preserves_the_saved_catalog_printing(self):
        _, added = self.request_json("/api/add", {
            "name": "Pikachu", "dex_id": 25, "page": 3, "slot": 7, "type": "Rare Holo",
            "condition": "LP", "notes": "old note", "catalog_card_id": "base1-25",
            "set_id": "base1", "set_name": "Base", "card_number": "25",
            "catalog_rarity": "Rare Holo", "image_url": "https://assets.tcgdex.net/en/base/base1/25/low.webp",
        })
        self.assertTrue(added["success"])
        before = pokemon_binder.load_collection()[0]

        status, updated = self.request_json("/api/update-card", {"copy_id": before["Copy ID"], "notes": "new note"})

        self.assertEqual(200, status, updated)
        after = pokemon_binder.load_collection()[0]
        self.assertEqual("new note", after["Notes"])
        for key in ("Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Rarity", "Image URL"):
            self.assertEqual(before[key], after[key], key)

    def test_card_details_update_edits_copy_fields_without_replacing_identity(self):
        _, added = self.request_json("/api/add", {
            "name": "Pikachu", "dex_id": 25, "page": 3, "slot": 7, "type": "Rare",
            "condition": "LP", "notes": "keep this", "grade": 8.5, "catalog_rarity": "Rare",
        })
        before = added["card"]

        status, updated = self.request_json("/api/update-card", {
            "copy_id": before["Copy ID"], "name": "Raichu", "page": 4, "slot": 2,
            "condition": "NM", "type": "Ultra Rare", "grade": 9.5,
            "date_added": "2026-10-16 12:30:00",
        })

        self.assertEqual(200, status, updated)
        saved = pokemon_binder.load_collection()[0]
        self.assertEqual(("Raichu", 4, 2, "NM", "Ultra Rare", "9.5"), (
            saved["Name"], saved["Page"], saved["Slot"], saved["Condition"], saved["Type"], saved["Grade"]
        ))
        self.assertEqual(before["Copy ID"], saved["Copy ID"])
        self.assertEqual("2026-10-16 12:30:00", saved["Date Added"])
        self.assertEqual("keep this", saved["Notes"])
        self.assertEqual("Rare", saved["Catalog Rarity"])

    def test_pokemon_database_response_is_not_cached_by_the_browser(self):
        with patch.object(pokemon_binder, "load_pokemon_database", return_value={"pikachu": {"id": 25}}):
            request = Request(self.base_url + "/api/pokemon-db")
            with urlopen(request, timeout=2) as response:
                self.assertIn("no-store", response.headers.get("Cache-Control", ""))

    def test_favicon_uses_bundled_icon_when_appdata_has_an_old_copy(self):
        (self.data_dir / "pokeball.ico").write_bytes(b"old icon")
        internal_data = Path(self.temp_dir.name) / "internal-data"
        internal_data.mkdir()
        (internal_data / "pokeball.ico").write_bytes(b"current icon")
        (internal_data / "pokemon.png").write_bytes(b"Pikachu artwork")

        with patch.object(pokemon_server, "INTERNAL_DATA_DIR", str(internal_data)):
            request = Request(self.base_url + "/favicon.ico")
            with urlopen(request, timeout=2) as response:
                self.assertEqual(b"current icon", response.read())
            request = Request(self.base_url + "/pokemon.png?v=1")
            with urlopen(request, timeout=2) as response:
                self.assertEqual(b"Pikachu artwork", response.read())

        html = (BINDER_DIR / "frontend" / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="/favicon.ico?v=2"', html)

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

    def test_first_sync_keeps_different_catalog_editions_separate(self):
        base = card("pikachu", dex=25)
        local = dict(base, **{"Catalog Card ID": "base1-25", "Set ID": "base1"})
        remote = dict(base, **{"Catalog Card ID": "neo3-12", "Set ID": "neo3"})
        self.remote = [remote]
        ok, _ = self.sync([local])
        self.assertTrue(ok)
        merged = pokemon_binder.load_collection()
        self.assertEqual({"base1-25", "neo3-12"}, {c.get("Catalog Card ID") for c in merged})

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

        default_browser.assert_called_once_with(f"http://127.0.0.1:{pokemon_server.PORT}")
        edge_app.assert_not_called()

    def test_tray_icon_uses_version_four_and_handles_its_callback_format(self):
        import ctypes

        class NotifyData(ctypes.Structure):
            _fields_ = [("uTimeoutOrVersion", ctypes.c_uint)]

        class FakeShell:
            def __init__(self):
                self.actions = []

            def Shell_NotifyIconW(self, action, notify_data):
                self.actions.append(action)
                return True

        opened = []
        tray = pokemon_server.SystemTrayIcon(lambda: opened.append(True), lambda: None)
        tray._shell = FakeShell()
        tray._notify_data = NotifyData()
        tray._add_icon()

        self.assertEqual([tray.NIM_ADD, 0x00000004], tray._shell.actions)
        self.assertEqual(4, tray._notify_data.uTimeoutOrVersion)
        tray._window_proc(1, tray.WM_TRAY, 0, (1 << 16) | tray.WM_LBUTTONDBLCLK)
        self.assertEqual([True], opened)

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

    def test_installer_launches_the_tray_server_and_packages_only_its_files(self):
        installer = (BINDER_DIR / "setup_script.iss").read_text(encoding="utf-8")
        build = (BINDER_DIR / "build.ps1").read_text(encoding="utf-8")
        launcher = r'Filename: "{app}\PokemonBinderServer\PokemonBinderServer.exe"'

        self.assertEqual(installer.count(launcher), 3)
        self.assertIn(
            r'Source: "dist\PokemonBinderServer\*"; DestDir: "{app}\PokemonBinderServer"; Flags: ignoreversion recursesubdirs createallsubdirs',
            installer,
        )
        self.assertIn(r'Source: "data\pokeball.ico"; DestDir: "{app}"; Flags: ignoreversion', installer)
        self.assertEqual(2, installer.count(r'IconFilename: "{app}\pokeball.ico"'))
        self.assertIn(r'Name: "{autodesktop}\Pokemon Binder Manager.lnk"', installer)
        self.assertNotIn("Tasks: desktopicon", installer)
        self.assertIn(r'SetupIconFile=data\pokeball.ico', installer)
        self.assertIn(r'--icon="$PSScriptRoot\data\pokeball.ico"', build)

    def test_windows_app_registers_a_visible_notification_area_icon(self):
        app = (BINDER_DIR / "src" / "Program.cs").read_text(encoding="utf-8")

        self.assertIn("new NotifyIcon", app)
        self.assertIn("Visible = true", app)

    def test_pokeball_app_icon_contains_windows_icon_sizes(self):
        icon = (BINDER_DIR / "data" / "pokeball.ico").read_bytes()
        reserved, image_type, count = struct.unpack_from("<HHH", icon)
        self.assertEqual((0, 1), (reserved, image_type))
        widths = {icon[6 + 16 * index] or 256 for index in range(count)}
        self.assertTrue({16, 32, 48, 128, 256}.issubset(widths))

    def test_packaged_server_leaves_window_to_webview(self):
        fake_server = Mock()
        with patch.object(pokemon_server, "acquire_single_instance", return_value=True), \
                patch.object(pokemon_binder, "load_pokemon_database", return_value={}), \
                patch.object(pokemon_binder, "download_default_icon"), \
                patch.object(pokemon_server, "ThreadedHTTPServer", return_value=fake_server), \
                patch.object(pokemon_server, "SystemTrayIcon", create=True) as tray, \
                patch.object(pokemon_server, "INSTANCE_TOKEN", "packaged-instance"), \
                patch("threading.Thread") as thread, \
                contextlib.redirect_stdout(io.StringIO()):
            pokemon_server.main()

        fake_server.serve_forever.assert_called_once()
        fake_server.server_close.assert_called_once()
        tray.assert_not_called()
        thread.assert_not_called()

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
