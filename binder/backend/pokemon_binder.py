import os
import sys
import csv
import json
import hashlib
import threading
import urllib.request
import urllib.error
import urllib.parse
import shutil
import uuid
from datetime import datetime

# Import secrets with fallback
try:
    try:
        from backend.firebase_secrets import ENC_FIREBASE_API_KEY, ENC_FIREBASE_DB_URL
    except ImportError:
        from firebase_secrets import ENC_FIREBASE_API_KEY, ENC_FIREBASE_DB_URL
except ImportError:
    ENC_FIREBASE_API_KEY = "PLACEHOLDER_ENC_API_KEY"
    ENC_FIREBASE_DB_URL = "PLACEHOLDER_ENC_DB_URL"

def decrypt_credential(enc_text):
    if enc_text in ("PLACEHOLDER_ENC_API_KEY", "PLACEHOLDER_ENC_DB_URL", ""):
        return ""
    import base64
    key = "PokeGraderSecureKey2026"
    try:
        raw_bytes = base64.b64decode(enc_text.encode('utf-8'))
        decoded_str = raw_bytes.decode('latin1')
        return "".join(chr(ord(c) ^ ord(key[i % len(key)])) for i, c in enumerate(decoded_str))
    except Exception:
        return ""

FIREBASE_API_KEY = decrypt_credential(ENC_FIREBASE_API_KEY)
FIREBASE_DB_URL = decrypt_credential(ENC_FIREBASE_DB_URL)


# Initialize ANSI color support on Windows
if sys.platform == 'win32':
    os.system('color')

def get_resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    try:
        # PyInstaller creates a temp folder and stores path in _MEIPASS
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    return os.path.join(base_path, relative_path)

def load_firebase_client_config():
    try:
        with open(get_resource_path(os.path.join("data", "firebase_client_config.json")), "r", encoding="utf-8") as f:
            config = json.load(f)
    except (OSError, ValueError):
        config = {}
    if not isinstance(config, dict):
        config = {}
    return {
        "apiKey": config.get("apiKey") or os.environ.get("POKEBINDER_FIREBASE_API_KEY", ""),
        "databaseURL": config.get("databaseURL") or os.environ.get("POKEBINDER_FIREBASE_DB_URL", ""),
    }

FIREBASE_CLIENT_CONFIG = load_firebase_client_config()
FIREBASE_API_KEY = FIREBASE_CLIENT_CONFIG["apiKey"] or FIREBASE_API_KEY
FIREBASE_DB_URL = FIREBASE_CLIENT_CONFIG["databaseURL"] or FIREBASE_DB_URL

# Configuration constants
# For data persistence, we want data/ to be in %APPDATA% so it's writable 
# even when the app is installed in Program Files.
if getattr(sys, 'frozen', False):
    # Running as a bundled EXE
    APPDATA = os.environ.get('APPDATA')
    DATA_DIR = os.path.join(APPDATA, "PokemonBinder", "data")
else:
    # Running in development
    DATA_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "data"))

CONFIG_FILE = os.path.join(DATA_DIR, "binder_config.json")
DATA_FILE = os.path.join(DATA_DIR, "pokemon_binder.csv")
CACHE_FILE = os.path.join(DATA_DIR, "pokemon_cache.json")
SYNC_STATE_FILE = os.path.join(DATA_DIR, "sync_state.json")
POKEMON_SPECIES_URL = "https://pokeapi.co/api/v2/pokemon-species/?limit=1000"
COLLECTION_LOCK = threading.RLock()
PROFILE_FIELDS = ("username", "profile_picture_source", "profile_featured_dex", "profile_image_url", "profile_image_path")
# shortcut: fixed at the current 1025 species; raise when the National Dex grows.
NATIONAL_DEX_SIZE = 1025

def account_data_path(user_id):
    account_hash = hashlib.sha256(str(user_id).encode("utf-8")).hexdigest()
    return os.path.join(DATA_DIR, "accounts", account_hash)

def _active_user_id(config=None):
    config = config if config is not None else load_config()
    user_id = str(config.get("firebase_user_id") or "").strip()
    return user_id if config.get("firebase_enabled") and user_id else ""

def _user_file(filename, user_id=None):
    if user_id is None:
        user_id = _active_user_id()
    if not user_id:
        return DATA_FILE if filename == "pokemon_binder.csv" else SYNC_STATE_FILE if filename == "sync_state.json" else os.path.join(DATA_DIR, filename)
    return os.path.join(account_data_path(user_id), filename)

def _profile_file(user_id=None):
    return _user_file("profile.json", user_id)

def local_profile_image_path(user_id=None):
    if user_id is None:
        user_id = _active_user_id()
    directory = account_data_path(user_id) if user_id else DATA_DIR
    return os.path.join(directory, "profile_image.png")

def _load_profile(user_id=None):
    try:
        with open(_profile_file(user_id), "r", encoding="utf-8") as f:
            profile = json.load(f)
        return profile if isinstance(profile, dict) else {}
    except (OSError, ValueError):
        return {}

def _save_profile(config, user_id=None):
    if user_id is None:
        user_id = _active_user_id(config)
    path = _profile_file(user_id)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    values = {key: config.get(key, DEFAULT_CONFIG.get(key, "")) for key in PROFILE_FIELDS}
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as f:
        json.dump(values, f, ensure_ascii=False, indent=2)
    os.replace(temporary, path)

def _migrate_legacy_user_data(config):
    user_id = _active_user_id(config)
    if not user_id or config.get("legacy_collection_owner") != user_id:
        return
    account_dir = account_data_path(user_id)
    os.makedirs(account_dir, exist_ok=True)
    for old_path, filename in ((DATA_FILE, "pokemon_binder.csv"), (SYNC_STATE_FILE, "sync_state.json")):
        target = os.path.join(account_dir, filename)
        if os.path.isfile(old_path) and not os.path.exists(target):
            backup = target + ".pre-account-migration.bak"
            if not os.path.exists(backup):
                shutil.copy2(old_path, backup)
            os.replace(old_path, target)
    config["legacy_collection_owner"] = "migrated:" + user_id
    save_config(config)

def import_ambiguous_legacy_collection(config):
    user_id = _active_user_id(config)
    if not user_id or config.get("firebase_auth_method") != "auth" or not config.get("firebase_id_token"):
        return False, "Sign in to a Firebase account before importing local cards."
    if config.get("legacy_collection_owner") != "ambiguous" or not os.path.isfile(DATA_FILE):
        return False, "No unassigned legacy collection is available to import."
    with COLLECTION_LOCK:
        backup = DATA_FILE + ".pre-explicit-import.bak"
        try:
            if not os.path.exists(backup):
                shutil.copy2(DATA_FILE, backup)
            legacy = _load_collection_file(DATA_FILE, user_id="")
            current = load_collection(user_id)
            known = {str(card.get("Copy ID")) for card in current}
            additions = [card for card in legacy if str(card.get("Copy ID")) not in known]
            if not save_collection(current + additions, user_id=user_id):
                return False, "Could not save the imported cards to this account. The original CSV is still available."
            config["legacy_collection_owner"] = "migrated:" + user_id
            save_config(config)
            return True, f"Copied {len(additions)} card copies into this account; the original CSV and a backup are kept on this PC."
        except Exception:
            return False, "Could not import the legacy collection. The original CSV is still available."

def load_sync_state(user_id=None):
    path = _user_file("sync_state.json", user_id)
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_sync_state(state, user_id=None):
    path = _user_file("sync_state.json", user_id)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        temporary = path + ".tmp"
        with open(temporary, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=4)
        os.replace(temporary, path)
    except Exception:
        pass

# Auto-create data directory if not exists
if not os.path.exists(DATA_DIR):
    try:
        os.makedirs(DATA_DIR)
    except Exception:
        pass

# Copy default templates if they do not exist
try:
    if not os.path.exists(CONFIG_FILE):
        default_config_path = get_resource_path(os.path.join("data", "binder_config.json.default"))
        if os.path.exists(default_config_path):
            shutil.copy(default_config_path, CONFIG_FILE)
    if not os.path.exists(DATA_FILE):
        default_data_path = get_resource_path(os.path.join("data", "pokemon_binder.csv.default"))
        if os.path.exists(default_data_path):
            shutil.copy(default_data_path, DATA_FILE)
except Exception:
    pass

DEFAULT_CONFIG = {
    "rows": 3,
    "cols": 3,
    "mode": "dex",  # "dex" (National Dex order) or "sequential" (fill order)
    "gsheet_enabled": False,
    "gsheet_name": "",
    "firebase_enabled": False,
    "firebase_db_url": "",
    "firebase_auth_method": "secret",  # "secret", "service_account", "auth", or "none"
    "firebase_secret": "",
    "firebase_user_id": "",
    "firebase_api_key": "",
    "firebase_email": "",
    "firebase_password": "",
    "firebase_id_token": "",
    "firebase_refresh_token": "",
    "firebase_remember_password": True,
    "legacy_collection_owner": "",
    "username": "Trainer",
    "profile_picture_source": "pokemon",
    "profile_featured_dex": 25,
    "profile_image_url": "",
    "profile_image_path": "",
    "cover_title": "Pokémon Collection",
    "cover_subtitle": "My Binder Manager",
    "cover_owner": "Ash",
    "cover_color": "#e02424",
    "cover_featured_dex": 25,
    "cover_source": "pokemon"
}

# Color constants
CLR_HEADER = "\033[95m"
CLR_SUCCESS = "\033[92m"
CLR_WARNING = "\033[93m"
CLR_FAIL = "\033[91m"
CLR_INFO = "\033[96m"
CLR_MUTED = "\033[90m"
CLR_RESET = "\033[0m"

def clear_screen():
    os.system('cls' if os.name == 'nt' else 'clear')

def get_gen1_fallback():
    # Hardcoded Gen 1 list with primary types for offline fallback
    gen1_data = [
        ("bulbasaur", "grass"), ("ivysaur", "grass"), ("venusaur", "grass"), 
        ("charmander", "fire"), ("charmeleon", "fire"), ("charizard", "fire"),
        ("squirtle", "water"), ("wartortle", "water"), ("blastoise", "water"),
        ("caterpie", "grass"), ("metapod", "grass"), ("butterfree", "grass"),
        ("weedle", "grass"), ("kakuna", "grass"), ("beedrill", "grass"),
        ("pidgey", "normal"), ("pidgeotto", "normal"), ("pidgeot", "normal"),
        ("rattata", "normal"), ("raticate", "normal"), ("spearow", "normal"), ("fearow", "normal"),
        ("ekans", "poison"), ("arbok", "poison"), ("pikachu", "electric"), ("raichu", "electric"),
        ("sandshrew", "ground"), ("sandslash", "ground"), ("nidoran-f", "poison"), ("nidorina", "poison"),
        ("nidoqueen", "poison"), ("nidoran-m", "poison"), ("nidorino", "poison"), ("nidoking", "poison"),
        ("clefairy", "fairy"), ("clefable", "fairy"), ("vulpix", "fire"), ("ninetales", "fire"),
        ("jigglypuff", "fairy"), ("wigglytuff", "fairy"), ("zubat", "poison"), ("golbat", "poison"),
        ("oddish", "grass"), ("gloom", "grass"), ("vileplume", "grass"), ("paras", "grass"),
        ("parasect", "grass"), ("venonat", "bug"), ("venomoth", "bug"), ("diglett", "ground"),
        ("dugtrio", "ground"), ("meowth", "normal"), ("persian", "normal"), ("psyduck", "water"),
        ("golduck", "water"), ("mankey", "fighting"), ("primeape", "fighting"), ("growlithe", "fire"),
        ("arcanine", "fire"), ("poliwag", "water"), ("poliwhirl", "water"), ("poliwrath", "water"),
        ("abra", "psychic"), ("kadabra", "psychic"), ("alakazam", "psychic"), ("machop", "fighting"),
        ("machoke", "fighting"), ("machamp", "fighting"), ("bellsprout", "grass"), ("weepinbell", "grass"),
        ("victreebel", "grass"), ("tentacool", "water"), ("tentacruel", "water"), ("geodude", "rock"),
        ("graveler", "rock"), ("golem", "rock"), ("ponyta", "fire"), ("rapidash", "fire"),
        ("slowpoke", "water"), ("slowbro", "water"), ("magnemite", "electric"), ("magneton", "electric"),
        ("farfetchd", "normal"), ("doduo", "normal"), ("dodrio", "normal"), ("seel", "water"),
        ("dewgong", "water"), ("grimer", "poison"), ("muk", "poison"), ("shellder", "water"),
        ("cloyster", "water"), ("gastly", "ghost"), ("haunter", "ghost"), ("gengar", "ghost"),
        ("onix", "rock"), ("drowzee", "psychic"), ("hypno", "psychic"), ("krabby", "water"),
        ("kingler", "water"), ("voltorb", "electric"), ("electrode", "electric"), ("exeggcute", "grass"),
        ("exeggutor", "grass"), ("cubone", "ground"), ("marowak", "ground"), ("hitmonlee", "fighting"),
        ("hitmonchan", "fighting"), ("lickitung", "normal"), ("koffing", "poison"), ("weezing", "poison"),
        ("rhyhorn", "ground"), ("rhydon", "ground"), ("chansey", "normal"), ("tangela", "grass"),
        ("kangaskhan", "normal"), ("horsea", "water"), ("seadra", "water"), ("goldeen", "water"),
        ("seaking", "water"), ("staryu", "water"), ("starmie", "water"), ("mr-mime", "psychic"),
        ("scyther", "bug"), ("jynx", "ice"), ("electabuzz", "electric"), ("magmar", "fire"),
        ("pinsir", "bug"), ("tauros", "normal"), ("magikarp", "water"), ("gyarados", "water"),
        ("lapras", "water"), ("ditto", "normal"), ("eevee", "normal"), ("vaporeon", "water"),
        ("jolteon", "electric"), ("flareon", "fire"), ("porygon", "normal"), ("omanyte", "rock"),
        ("omastar", "rock"), ("kabuto", "rock"), ("kabutops", "rock"), ("aerodactyl", "rock"),
        ("snorlax", "normal"), ("articuno", "ice"), ("zapdos", "electric"), ("moltres", "fire"),
        ("dratini", "dragon"), ("dragonair", "dragon"), ("dragonite", "dragon"), ("mewtwo", "psychic"),
        ("mew", "psychic")
    ]
    db = {}
    for index, (name, p_type) in enumerate(gen1_data):
        dex_id = index + 1
        db[name] = {"id": dex_id, "type": p_type}
        db[str(dex_id)] = {"name": name, "type": p_type}
    return db

def _pokemon_cache_id(value):
    value = value.get("id") if isinstance(value, dict) else value
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _pokemon_cache_is_complete(db):
    if not isinstance(db, dict):
        return False
    ids = {
        _pokemon_cache_id(value)
        for name, value in db.items()
        if not name.isdigit()
    }
    return all(dex_id in ids for dex_id in range(1, NATIONAL_DEX_SIZE + 1))


def _pokemon_cache_types(db):
    types = {}
    for key, value in db.items():
        if not isinstance(value, dict) or not value.get("type"):
            continue
        name = value.get("name") if key.isdigit() else key
        if isinstance(name, str):
            types[name] = value["type"]
    return types


def _download_pokemon_species():
    url = POKEMON_SPECIES_URL
    pages = set()
    species = []
    expected_count = None
    while url:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname != "pokeapi.co" or parsed.path.rstrip("/") != "/api/v2/pokemon-species":
            raise ValueError("PokéAPI returned an invalid species page URL")
        if url in pages:
            raise ValueError("PokéAPI repeated a species page")
        pages.add(url)
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=10) as response:
            page = json.loads(response.read().decode("utf-8"))
        if not isinstance(page, dict) or not isinstance(page.get("results"), list):
            raise ValueError("PokéAPI returned an invalid species page")
        count = page.get("count")
        if isinstance(count, bool) or not isinstance(count, int) or count < NATIONAL_DEX_SIZE:
            raise ValueError("PokéAPI returned an incomplete National Pokédex")
        if expected_count is not None and count != expected_count:
            raise ValueError("PokéAPI species count changed during download")
        expected_count = count
        species.extend(page["results"])
        next_url = page.get("next")
        if next_url is not None and not isinstance(next_url, str):
            raise ValueError("PokéAPI returned an invalid next page")
        url = next_url

    records = {}
    names = set()
    for item in species:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ValueError("PokéAPI returned an invalid species")
        item_url = urllib.parse.urlsplit(item.get("url", ""))
        if item_url.scheme != "https" or item_url.hostname != "pokeapi.co" or item_url.path.rstrip("/").rsplit("/", 1)[0] != "/api/v2/pokemon-species":
            raise ValueError("PokéAPI returned a non-species resource")
        dex_id = int(item_url.path.rstrip("/").rsplit("/", 1)[-1])
        if dex_id < 1 or dex_id in records or item["name"] in names:
            raise ValueError("PokéAPI returned duplicate species")
        records[dex_id] = {"name": item["name"]}
        names.add(item["name"])
    if len(records) != expected_count or set(records) != set(range(1, expected_count + 1)):
        raise ValueError("PokéAPI species list is incomplete")
    return records


def _save_pokemon_database(db):
    temporary_file = CACHE_FILE + ".tmp"
    try:
        with open(temporary_file, "w", encoding="utf-8") as f:
            json.dump(db, f, indent=4)
        os.replace(temporary_file, CACHE_FILE)
    finally:
        if os.path.exists(temporary_file):
            os.remove(temporary_file)


def load_pokemon_database():
    cached_db = None
    cache_paths = [CACHE_FILE, get_resource_path(os.path.join("data", "pokemon_cache.json"))]
    for path in dict.fromkeys(cache_paths):
        try:
            with open(path, "r", encoding="utf-8") as f:
                candidate = json.load(f)
            if isinstance(candidate, dict):
                cached_db = candidate
                break
        except (OSError, ValueError):
            continue
    if _pokemon_cache_is_complete(cached_db):
        return cached_db

    print(f"{CLR_INFO}[i] Loading the National Pokédex species database...{CLR_RESET}")
    try:
        species = _download_pokemon_species()
        known_types = _pokemon_cache_types(cached_db or {})
        db = {}
        for dex_id, item in species.items():
            name = item["name"]
            entry = {"id": dex_id}
            if name in known_types:
                entry["type"] = known_types[name]
            db[name] = entry
            reverse_entry = {"name": name}
            if "type" in entry:
                reverse_entry["type"] = entry["type"]
            db[str(dex_id)] = reverse_entry
        _save_pokemon_database(db)
        return db
    except Exception as e:
        print(f"{CLR_WARNING}[!] Warning: Could not update database ({e}).{CLR_RESET}")
        return cached_db if cached_db is not None else get_gen1_fallback()
def download_default_icon():
    icon_path = os.path.join(DATA_DIR, "pokeball.ico")
    if not os.path.exists(icon_path):
        print(f"{CLR_INFO}[i] Downloading Poke Ball icon...{CLR_RESET}")
        try:
            url = "https://raw.githubusercontent.com/LeBronWilly/Pokemon_Info/main/pokeball.ico"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=5) as response:
                with open(icon_path, "wb") as f:
                    f.write(response.read())
            print(f"{CLR_SUCCESS}[+] Downloaded Poke Ball icon successfully!{CLR_RESET}")
        except Exception:
            try:
                url_fallback = "https://raw.githubusercontent.com/driss-khelfi/pokemon/master/pokeball.ico"
                req = urllib.request.Request(url_fallback, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=5) as response:
                    with open(icon_path, "wb") as f:
                        f.write(response.read())
            except Exception:
                pass


def find_pokemon(query, db):
    query = query.strip().lower()
    if not query:
        return None, None
        
    # Check exact match (name or ID)
    if query in db:
        if query.isdigit():
            value = db[query]
            return int(query), value.get("name") if isinstance(value, dict) else value
        else:
            return _pokemon_cache_id(db[query]), query
            
    # Try partial name matching in cached db
    matches = [name for name in db.keys() if not name.isdigit() and query in name]
    if len(matches) == 1:
        return _pokemon_cache_id(db[matches[0]]), matches[0]
    elif len(matches) > 1:
        return "multiple", sorted(matches)
        
    # Query PokeAPI directly in case it's a newer species not in cache
    try:
        url = f"https://pokeapi.co/api/v2/pokemon-species/{query.replace(' ', '-')}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as response:
            data = json.loads(response.read().decode())
            name = data["name"]
            dex_id = data["id"]
            
            # Update cache
            db[name] = {"id": dex_id}
            db[str(dex_id)] = {"name": name}
            _save_pokemon_database(db)
            return dex_id, name
    except Exception:
        pass
        
    return None, None

def load_config():
    config_loaded = False
    config = DEFAULT_CONFIG.copy()
    loaded = {}
    
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                # Merge into our copy
                for k, v in loaded.items():
                    config[k] = v
                config_loaded = True
        except Exception:
            pass
            
    # Always ensure all keys from DEFAULT_CONFIG exist
    for k, v in DEFAULT_CONFIG.items():
        if k not in config:
            config[k] = v
            
    user_id = _active_user_id(config)
    profile_path = _profile_file(user_id)
    if not os.path.exists(profile_path) and any(key in loaded for key in PROFILE_FIELDS):
        _save_profile({**DEFAULT_CONFIG, **loaded}, user_id)
    config.update(_load_profile(user_id))

    if os.path.isfile(DATA_FILE) and not config.get("legacy_collection_owner"):
        config["legacy_collection_owner"] = user_id or "ambiguous"
        save_config(config)
    elif not config_loaded:
        save_config(config)

    _migrate_legacy_user_data(config)
    return config

def save_config(config):
    try:
        user_id = _active_user_id(config)
        _save_profile(config, user_id)
        persisted = dict(config)
        for key in PROFILE_FIELDS:
            persisted.pop(key, None)
        persisted["firebase_password"] = ""
        temporary = CONFIG_FILE + ".tmp"
        with open(temporary, "w", encoding="utf-8") as f:
            json.dump(persisted, f, indent=4, ensure_ascii=False)
        os.replace(temporary, CONFIG_FILE)
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error saving configuration: {e}{CLR_RESET}")

COLLECTION_FIELDS = ["Page", "Slot", "Dex Number", "Name", "Species Name", "Form", "Card Language", "Type", "Condition", "Notes", "Date Added", "Image URL", "Copy ID", "Catalog Provider", "Catalog Locale", "Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Variant", "Catalog Rarity", "Grade", "Grade Details", "Image Source", "Image Match ID"]
OPTIONAL_COLLECTION_FIELDS = set(COLLECTION_FIELDS) - {"Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added", "Copy ID"}

def legacy_copy_id(card, index):
    identity = "|".join(str(card.get(field, "") or "") for field in (
        "Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added", "Catalog Card ID", "Set ID", "Card Number"
    )) + "|" + str(index)
    return "legacy-" + hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]

def load_collection(user_id=None):
    if user_id is None:
        config = load_config()
        user_id = _active_user_id(config)
        if user_id:
            _migrate_legacy_user_data(config)
    path = _user_file("pokemon_binder.csv", user_id)
    return _load_collection_file(path, user_id)

def _load_collection_file(path, user_id=None):
    collection = []
    migration_needed = False
    if os.path.exists(path):
        try:
            with open(path, "r", newline="", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                # If 'Type' is missing from the headers, we must trigger migration
                has_type_column = "Type" in reader.fieldnames if reader.fieldnames else False
                if not has_type_column:
                    migration_needed = True
                if reader.fieldnames and any(field not in reader.fieldnames for field in COLLECTION_FIELDS):
                    migration_needed = True
                
                for index, row in enumerate(reader):
                    # Parse numerical fields safely
                    row["Page"] = int(row["Page"])
                    row["Slot"] = int(row["Slot"])
                    row["Dex Number"] = int(row["Dex Number"]) if row["Dex Number"] else 0
                    if not row.get("Copy ID"):
                        row["Copy ID"] = legacy_copy_id(row, index)
                        migration_needed = True
                    catalog_card_id = str(row.get("Catalog Card ID") or "").strip()
                    catalog_provider = str(row.get("Catalog Provider") or "").strip()
                    catalog_locale = str(row.get("Catalog Locale") or "").strip()
                    # Old IDs came from the English-only TCGdex path; physical language remains unknown.
                    if catalog_card_id and not catalog_provider:
                        row["Catalog Provider"] = "TCGdex"
                        catalog_provider = "TCGdex"
                        migration_needed = True
                    if catalog_card_id and catalog_provider == "TCGdex" and not catalog_locale:
                        row["Catalog Locale"] = "en"
                        migration_needed = True
                    if not row.get("Image URL"):
                        row.pop("Image URL", None)
                    for field in OPTIONAL_COLLECTION_FIELDS:
                        if not row.get(field):
                            row.pop(field, None)
                    
                    # Ensure Type column exists
                    if "Type" not in row or row["Type"] is None:
                        row["Type"] = "Normal"
                    
                    # Check Notes for rarity keywords first
                    notes_val = str(row.get("Notes", "")).strip()
                    notes_val_lower = notes_val.lower()
                    
                    if notes_val_lower in ["reverse", "reverse holo"]:
                        row["Type"] = "Reverse Holo"
                        row["Notes"] = ""
                        migration_needed = True
                    elif notes_val_lower in ["ir", "illustration rare", "ilustration rare"]:
                        row["Type"] = "Illustration Rare"
                        row["Notes"] = ""
                        migration_needed = True
                    elif notes_val_lower in ["sir", "special illustration rare"]:
                        row["Type"] = "Special Illustration Rare"
                        row["Notes"] = ""
                        migration_needed = True
                    elif notes_val_lower in ["holo", "holofoil"]:
                        row["Type"] = "Holofoil Rare"
                        row["Notes"] = ""
                        migration_needed = True
                    elif notes_val_lower in ["common", "uncommon"]:
                        row["Type"] = "Normal"
                        row["Notes"] = ""
                        migration_needed = True
                    
                    # Normalizations for Type column directly
                    t_val = str(row.get("Type", "")).strip()
                    t_val_lower = t_val.lower()
                    if t_val == "IR" or t_val_lower == "ir" or t_val_lower == "ilustration rare":
                        row["Type"] = "Illustration Rare"
                        migration_needed = True
                    elif t_val == "SIR" or t_val_lower == "sir":
                        row["Type"] = "Special Illustration Rare"
                        migration_needed = True
                    elif t_val == "SR" or t_val_lower == "sr":
                        row["Type"] = "Secret Rare"
                        migration_needed = True
                    elif t_val == "HR" or t_val_lower == "hr":
                        row["Type"] = "Hyper Rare"
                        migration_needed = True
                    elif t_val == "Holo" or t_val_lower == "holo" or t_val_lower == "holofoil":
                        row["Type"] = "Holofoil Rare"
                        migration_needed = True
                    elif t_val_lower in ["common", "uncommon"]:
                        row["Type"] = "Normal"
                        migration_needed = True
                        
                    collection.append(row)
            
            if migration_needed:
                print(f"{CLR_INFO}[i] Database migration triggered: upgrading old rarity fields...{CLR_RESET}")
                backup = path + ".pre-migration.bak"
                if not os.path.exists(backup):
                    shutil.copy2(path, backup)
                save_collection(collection, user_id=user_id)
        except Exception as e:
            print(f"{CLR_FAIL}[!] Error reading collection CSV: {e}{CLR_RESET}")
    return collection

def save_collection(collection, user_id=None):
    fieldnames = COLLECTION_FIELDS
    path = _user_file("pokemon_binder.csv", user_id)
    try:
        with COLLECTION_LOCK:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            temporary = path + ".tmp"
            with open(temporary, "w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=fieldnames)
                writer.writeheader()
                for card in collection:
                    row = {k: card.get(k, "") for k in fieldnames}
                    writer.writerow(row)
            os.replace(temporary, path)
        return True
    except Exception as e:
        if os.path.exists(path + ".tmp"):
            os.remove(path + ".tmp")
        print(f"{CLR_FAIL}[!] Error saving collection: {e}{CLR_RESET}")
        return False

def add_card_to_csv(card):
    with COLLECTION_LOCK:
        collection = load_collection()
        collection.append(card)
        return save_collection(collection)

# ==================== GOOGLE SHEETS SYNC MODULE ====================

def get_credentials_path():
    """ Try multiple locations for credentials.json and return the first one that exists """
    possible_paths = [
        # 1. Next to the script (dev) or in _internal (frozen)
        os.path.abspath(os.path.join(os.path.dirname(__file__), "credentials.json")),
        # 2. In the persistent DATA_DIR (best for installed apps)
        os.path.join(DATA_DIR, "credentials.json"),
        # 3. Next to the executable (convenient for users)
        os.path.join(os.path.dirname(sys.executable), "credentials.json")
    ]
    
    for path in possible_paths:
        if os.path.exists(path):
            return path
    return None

def get_gspread_client():
    try:
        import gspread
        from google.oauth2.service_account import Credentials
    except ImportError:
        return None, "Google Sheets libraries (gspread, google-auth) are not installed.\n       Please run via 'run_binder.bat' to install dependencies."
        
    credentials_path = get_credentials_path()
    if not credentials_path:
        return None, f"credentials.json key file is missing.\n       Please place it in: {DATA_DIR}\n       Run Settings -> option 3 for help."
        
    try:
        scopes = [
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/drive"
        ]
        credentials = Credentials.from_service_account_file(credentials_path, scopes=scopes)
        client = gspread.authorize(credentials)
        return client, None
    except Exception as e:
        return None, f"Failed to authorize API client: {e}"

def sync_with_google_sheets(config, collection):
    if not config.get("gsheet_enabled"):
        return False, "Google Sheets sync is currently disabled."
        
    client, err = get_gspread_client()
    if err:
        return False, err
        
    sheet_name = config.get("gsheet_name")
    if not sheet_name:
        return False, "Google Spreadsheet name is not configured in settings."
        
    try:
        sh = client.open(sheet_name)
        
        # Get or add sheets worksheet
        try:
            worksheet = sh.worksheet("Binder Collection")
        except Exception:
            worksheet = sh.add_worksheet(title="Binder Collection", rows="1000", cols=str(len(COLLECTION_FIELDS)))
            
        # Write headers
        headers = COLLECTION_FIELDS
        
        if worksheet.col_count < len(headers):
            worksheet.resize(cols=len(headers))

        # Assemble rows
        rows_to_write = []
        for card in collection:
            rows_to_write.append([card.get(field, "") or "" for field in headers])
            
        worksheet.clear()
        worksheet.append_row(headers)
        if rows_to_write:
            worksheet.append_rows(rows_to_write)
            
        return True, f"Successfully synced {len(collection)} cards to Google Sheet '{sheet_name}'!"
    except Exception as e:
        # Give helpful tip on SpreadsheetNotFound
        if "SpreadsheetNotFound" in str(type(e)):
            email = "your-service-account-email"
            cp = get_credentials_path()
            if cp:
                try:
                    with open(cp) as f:
                        email = json.load(f).get("client_email", email)
                except Exception:
                    pass
            return False, f"Spreadsheet '{sheet_name}' was not found.\n       Ensure the sheet is created in your Google Drive and shared with:\n       {CLR_WARNING}{email}{CLR_RESET}"
        return False, f"Sync error: {e}"

def print_google_sheets_setup_guide():
    email = "your-service-account-email@...gserviceaccount.com"
    cp = get_credentials_path()
    if cp:
        try:
            with open(cp) as f:
                email = json.load(f).get("client_email", email)
        except Exception:
            pass
            
    print(f"\n{CLR_HEADER}=== GOOGLE SHEETS SETUP INSTRUCTIONS ==={CLR_RESET}")
    print("To sync your binder database with Google Sheets:")
    print("1. Go to the Google Cloud Console (https://console.cloud.google.com/).")
    print("2. Create a new project and enable 'Google Sheets API' & 'Google Drive API'.")
    print("3. Go to 'Credentials' -> Click '+ CREATE CREDENTIALS' -> select 'Service Account'.")
    print("4. Under the 'Keys' tab of that account, click 'Add Key' -> 'Create new key' -> JSON.")
    print("5. Rename the downloaded JSON file to: \033[92mcredentials.json\033[0m")
    print("6. Where to place it:")
    print(f"   - Installed: Paste into: \033[96m{DATA_DIR}\033[0m")
    print("   - Source: Place inside the 'backend' folder.")
    print("7. Create a Google Sheet and share it with your Service Account email:")
    print(f"   {CLR_WARNING}{email}{CLR_RESET}")
    print("8. Enable Sheets Sync and set the Spreadsheet Name in Settings!")

# ==================== FIREBASE SYNC MODULE ====================

def make_firebase_request(url, method="GET", data=None, token=None, request_headers=None, return_etag=False):
    import urllib.request
    import urllib.error
    import json
    
    headers = {
        "Content-Type": "application/json"
    }
    if request_headers:
        headers.update(request_headers)
    if return_etag and method == "GET":
        headers["X-Firebase-ETag"] = "true"
    if token:
        if token.startswith("ya29."):
            headers["Authorization"] = f"Bearer {token}"
        else:
            import urllib.parse
            connector = "&" if "?" in url else "?"
            url = f"{url}{connector}auth={urllib.parse.quote(token)}"
        
    req_data = None
    if data is not None:
        req_data = json.dumps(data).encode("utf-8")
        
    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            res_content = response.read().decode("utf-8")
            result = json.loads(res_content) if res_content else None
            return (result, None, response.headers.get("ETag")) if return_etag else (result, None)
    except urllib.error.HTTPError as e:
        try:
            err_msg = e.read().decode("utf-8")
            err_data = json.loads(err_msg)
            error = err_data.get('error', {})
            message = error.get('message', 'Request rejected') if isinstance(error, dict) else 'Request rejected'
            result = None
            if e.code == 412:
                try:
                    result = json.loads(err_msg)
                except ValueError:
                    pass
            response = (result, "ETAG_MISMATCH", e.headers.get("ETag")) if e.code == 412 else (None, f"HTTP Error {e.code}: {message}", e.headers.get("ETag"))
            return response if return_etag else response[:2]
        except Exception:
            response = (None, "ETAG_MISMATCH", e.headers.get("ETag")) if e.code == 412 else (None, f"HTTP Error {e.code}: {e.reason}", e.headers.get("ETag"))
            return response if return_etag else response[:2]
    except Exception:
        return (None, "Connection error while contacting Firebase.", None) if return_etag else (None, "Connection error while contacting Firebase.")

def get_firebase_oauth_token():
    try:
        from google.oauth2 import service_account
        import google.auth.transport.requests
    except ImportError:
        return None, "google-auth libraries are not installed."
        
    credentials_path = get_credentials_path()
    if not credentials_path:
        return None, "credentials.json service account key is missing."
        
    try:
        scopes = [
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/firebase.database"
        ]
        credentials = service_account.Credentials.from_service_account_file(credentials_path, scopes=scopes)
        auth_req = google.auth.transport.requests.Request()
        credentials.refresh(auth_req)
        return credentials.token, None
    except Exception as e:
        return None, f"OAuth2 token generation failed: {e}"

def firebase_sign_in(config):
    api_key = FIREBASE_API_KEY or config.get("firebase_api_key")
    email = config.get("firebase_email")
    password = config.get("firebase_password")
    
    if not api_key or not email or not password:
        return False, "Missing API Key, Email, or Password in settings."

    previous_user_id = _active_user_id(config)
    _save_profile(config, previous_user_id)
        
    url = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={api_key}"
    data = {
        "email": email,
        "password": password,
        "returnSecureToken": True
    }
    
    res_data, err = make_firebase_request(url, method="POST", data=data)
    if err:
        return False, f"Login failed: {err}"
        
    if res_data and "idToken" in res_data:
        config["firebase_id_token"] = res_data["idToken"]
        config["firebase_user_id"] = res_data["localId"]
        config["firebase_refresh_token"] = res_data.get("refreshToken", "")
        config["firebase_auth_method"] = "auth"
        config["firebase_enabled"] = True
        config.update(_load_profile(config["firebase_user_id"]))
        config["firebase_password"] = ""
        save_config(config)
        return True, "Login successful!"
    return False, "Invalid response from authentication server."

def firebase_refresh_id_token(config):
    api_key = FIREBASE_API_KEY or config.get("firebase_api_key")
    refresh_token = config.get("firebase_refresh_token")
    if not api_key or not refresh_token:
        return False, "Sign in to Firebase again to refresh your session."
    try:
        body = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": refresh_token}).encode("utf-8")
        req = urllib.request.Request(
            f"https://securetoken.googleapis.com/v1/token?key={api_key}",
            data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            result = json.loads(response.read().decode("utf-8"))
        config["firebase_id_token"] = result["id_token"]
        config["firebase_refresh_token"] = result.get("refresh_token", refresh_token)
        config["firebase_user_id"] = result.get("user_id", config.get("firebase_user_id", ""))
        save_config(config)
        return True, "Firebase session refreshed."
    except Exception:
        return False, "Firebase session expired; sign in again."

def firebase_sign_up(config, email, password):
    api_key = FIREBASE_API_KEY or config.get("firebase_api_key")
    if not api_key:
        return False, "Missing Firebase Web API Key in settings."

    _save_profile(config, _active_user_id(config))
        
    url = f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={api_key}"
    data = {
        "email": email,
        "password": password,
        "returnSecureToken": True
    }
    
    res_data, err = make_firebase_request(url, method="POST", data=data)
    if err:
        return False, f"Registration failed: {err}"
        
    if res_data and "idToken" in res_data:
        config["firebase_email"] = email
        config["firebase_password"] = ""
        config["firebase_id_token"] = res_data["idToken"]
        config["firebase_user_id"] = res_data["localId"]
        config["firebase_refresh_token"] = res_data.get("refreshToken", "")
        config["firebase_auth_method"] = "auth"
        config["firebase_enabled"] = True
        config.update(_load_profile(config["firebase_user_id"]))
        save_config(config)
        return True, "Registration successful!"
    return False, "Invalid response from registration server."

def firebase_logout(config):
    user_id = _active_user_id(config)
    _save_profile(config, user_id)
    config["firebase_enabled"] = False
    config["firebase_email"] = ""
    config["firebase_password"] = ""
    config["firebase_id_token"] = ""
    config["firebase_refresh_token"] = ""
    config["firebase_user_id"] = ""
    config.update(_load_profile(""))
    save_config(config)
    return True, "Disconnected from Firebase. This Windows profile's collection is local again."

SYNC_PRINTING_FIELDS = ("Name", "Species Name", "Dex Number", "Form", "Card Language", "Catalog Provider", "Catalog Locale", "Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Variant", "Catalog Rarity", "Image URL", "Image Source", "Image Match ID")

def _remote_cards(data):
    values = data if isinstance(data, list) else list(data.values()) if isinstance(data, dict) else []
    result = {}
    deleted = set()
    for index, value in enumerate(values):
        if not isinstance(value, dict):
            continue
        card = dict(value)
        if not card.get("Copy ID"):
            card["Copy ID"] = legacy_copy_id(card, index)
        copy_id = str(card["Copy ID"])
        if card.get("_deleted"):
            deleted.add(copy_id)
        else:
            result[copy_id] = card
    return result, deleted

def _changed_fields(base, changed):
    return [field for field in COLLECTION_FIELDS if field != "Copy ID" and str(base.get(field, "") or "") != str(changed.get(field, "") or "")]

def _merge_copy(base, local, remote):
    merged = dict(remote)
    conflicts = []
    local_printing_changed = any(str(base.get(field, "") or "") != str(local.get(field, "") or "") for field in SYNC_PRINTING_FIELDS)
    remote_printing_changed = any(str(base.get(field, "") or "") != str(remote.get(field, "") or "") for field in SYNC_PRINTING_FIELDS)
    if local_printing_changed:
        if remote_printing_changed and any(str(local.get(field, "") or "") != str(remote.get(field, "") or "") for field in SYNC_PRINTING_FIELDS):
            conflicts.append("printing")
        for field in SYNC_PRINTING_FIELDS:
            merged[field] = local.get(field, "")
    for field in COLLECTION_FIELDS:
        if field == "Copy ID" or field in SYNC_PRINTING_FIELDS:
            continue
        base_value = str(base.get(field, "") or "")
        local_value = str(local.get(field, "") or "")
        remote_value = str(remote.get(field, "") or "")
        if local_value == base_value:
            continue
        if remote_value != base_value and remote_value != local_value:
            conflicts.append(field)
        merged[field] = local.get(field, "")
    merged["Copy ID"] = local.get("Copy ID") or remote.get("Copy ID", "")
    return merged, conflicts

def _save_sync_conflicts(user_id, entries):
    if not entries:
        return True
    path = _user_file("sync_conflicts.json", user_id)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        existing = []
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                existing = json.load(f)
        known = {json.dumps(item, sort_keys=True, ensure_ascii=False) for item in existing if isinstance(item, dict)}
        for item in entries:
            serialized = json.dumps(item, sort_keys=True, ensure_ascii=False)
            if serialized not in known:
                existing.append(item)
                known.add(serialized)
        temporary = path + ".tmp"
        with open(temporary, "w", encoding="utf-8") as f:
            json.dump(existing, f, ensure_ascii=False, indent=2)
        os.replace(temporary, path)
        return True
    except Exception:
        return False

def _copy_url(collection_url, copy_id):
    return collection_url[:-5] + "/" + urllib.parse.quote(str(copy_id), safe="") + ".json"

def _conditional_write_copy(collection_url, user_id, token, copy_id, base=None, local=None, delete=False, add=False):
    copy_url = _copy_url(collection_url, copy_id)
    for _ in range(3):
        current, err, etag = make_firebase_request(copy_url, method="GET", token=token, return_etag=True)
        if err:
            return False, err
        conflict = ""
        if delete:
            if not isinstance(current, dict) or current.get("_deleted"):
                return True, ""
            if base and _changed_fields(base, current):
                if not _save_sync_conflicts(user_id, [{"copy_id": copy_id, "reason": "local-delete-remote-edit", "base": base, "local": None, "remote": current}]):
                    return False, "Could not save the recovery record for a conflicting edit."
                conflict = "local-delete-remote-edit"
            updated = dict(current, _deleted=True)
        elif current is None:
            if not add:
                if base and _changed_fields(base, local or {}):
                    if not _save_sync_conflicts(user_id, [{"copy_id": copy_id, "reason": "remote-delete-local-edit", "base": base, "local": local, "remote": None}]):
                        return False, "Could not save the recovery record for a removed card."
                return True, ""
            updated = dict(local or {})
        elif current.get("_deleted"):
            if add:
                return False, "This copy was removed and cannot be restored with the same Copy ID."
            if base and _changed_fields(base, local or {}):
                if not _save_sync_conflicts(user_id, [{"copy_id": copy_id, "reason": "remote-delete-local-edit", "base": base, "local": local, "remote": None}]):
                    return False, "Could not save the recovery record for a removed card."
            return True, ""
        elif add:
            return True, ""
        else:
            updated, fields = _merge_copy(base or {}, local or {}, current)
            if fields:
                record = {"copy_id": copy_id, "reason": "same-field-edit", "fields": fields, "base": base, "local": local, "remote": current}
                if not _save_sync_conflicts(user_id, [record]):
                    return False, "Could not save the recovery record for a conflicting edit."
                conflict = "same-field-edit"
        updated.pop("_deleted", None) if not delete else None
        if not delete and current and updated == current:
            return True, ""
        _, err, _ = make_firebase_request(
            copy_url, method="PUT", data=updated, token=token,
            request_headers={"If-Match": etag or "null_etag"}, return_etag=True
        )
        if err == "ETAG_MISMATCH":
            continue
        if err:
            return False, err
        return True, conflict
    return False, "The same card changed repeatedly during sync; retry after refreshing."

def sync_with_firebase(config, collection):
    with COLLECTION_LOCK:
        return _sync_with_firebase(config, collection)

def _sync_with_firebase(config, collection):
    if not config.get("firebase_enabled"):
        return False, "Firebase sync is currently disabled."
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    user_id = str(config.get("firebase_user_id") or "").strip()
    if not db_url:
        return False, "Firebase client configuration is missing; the binder remains available in local mode."
    if not user_id:
        return False, "Sign in to Firebase before syncing this collection."
    auth_method = config.get("firebase_auth_method", "auth")
    if auth_method in ("secret", "service_account"):
        return False, "Collection sync requires a personal Firebase Auth account; administrator credentials are not accepted."
    if not config.get("firebase_id_token") and config.get("firebase_refresh_token"):
        refreshed, message = firebase_refresh_id_token(config)
        if not refreshed:
            return False, message
    token = config.get("firebase_id_token")
    if not token and auth_method != "none":
        return False, "Sign in to Firebase before syncing this collection."
    db_url = db_url.strip()
    if not db_url.startswith("http"):
        db_url = "https://" + db_url
    user_path = f"{db_url.rstrip('/')}/users/{urllib.parse.quote(user_id, safe='')}"
    collection_url = user_path + "/collection_v2.json"
    legacy_url = user_path + "/collection.json"

    remote_data, err = make_firebase_request(collection_url, method="GET", token=token)
    if err and "HTTP Error 401" in err and auth_method == "auth":
        refreshed, message = firebase_refresh_id_token(config)
        if not refreshed:
            return False, message
        token = config.get("firebase_id_token")
        remote_data, err = make_firebase_request(collection_url, method="GET", token=token)
    if err:
        return False, f"Failed to retrieve Firebase data: {err}"

    remote_all, remote_deleted = _remote_cards(remote_data)
    if remote_data is None:
        legacy_data, err = make_firebase_request(legacy_url, method="GET", token=token)
        if err:
            return False, f"Failed to retrieve legacy Firebase data: {err}"
        legacy_cards, _ = _remote_cards(legacy_data)
        for copy_id, old_card in legacy_cards.items():
            success, message = _conditional_write_copy(collection_url, user_id, token, copy_id, local=old_card, add=True)
            if not success:
                return False, f"Could not migrate the legacy Firebase collection: {message}"
        if legacy_cards:
            remote_data, err = make_firebase_request(collection_url, method="GET", token=token)
            if err:
                return False, f"Failed to read the migrated Firebase collection: {err}"
            remote_all, remote_deleted = _remote_cards(remote_data)

    cloud_profile = fetch_profile_from_firebase(config)
    if cloud_profile:
        for key in PROFILE_FIELDS:
            profile_key = {"username": "username", "profile_picture_source": "profile_picture_source", "profile_featured_dex": "profile_featured_dex", "profile_image_url": "profile_image_url"}.get(key, key)
            if profile_key in cloud_profile:
                config[key] = cloud_profile[profile_key]
        base64_img = cloud_profile.get("profile_image_base64")
        if base64_img and config.get("profile_picture_source") in ["upload", "base64"]:
            try:
                import base64 as b64
                filepath = local_profile_image_path(user_id)
                os.makedirs(os.path.dirname(filepath), exist_ok=True)
                with open(filepath, "wb") as f:
                    f.write(b64.b64decode(base64_img))
                config["profile_image_path"] = "profile_image.png"
                if config.get("profile_picture_source") == "base64":
                    config["profile_picture_source"] = "upload"
            except Exception:
                pass
        save_config(config)

    def indexed(cards):
        return {str(card.get("Copy ID")): dict(card) for card in cards if card.get("Copy ID")}

    local_cards = [dict(card) for card in collection]
    for index, card in enumerate(local_cards):
        if not card.get("Copy ID"):
            card["Copy ID"] = legacy_copy_id(card, index)
    local = indexed(local_cards)
    base = indexed(load_sync_state(user_id))
    conflict_count = 0
    is_first_sync = not base

    if is_first_sync:
        for copy_id, card in local.items():
            if copy_id not in remote_all and copy_id not in remote_deleted:
                ok, message = _conditional_write_copy(collection_url, user_id, token, copy_id, local=card, add=True)
                if not ok:
                    return False, f"Failed to add a card to Firebase: {message}"
    else:
        for copy_id, old_card in base.items():
            local_card = local.get(copy_id)
            remote_card = remote_all.get(copy_id)
            if local_card is None:
                if remote_card is not None:
                    ok, message = _conditional_write_copy(collection_url, user_id, token, copy_id, base=old_card, delete=True)
                    if not ok:
                        return False, f"Failed to remove a card from Firebase: {message}"
                    conflict_count += int("local-delete-remote-edit" in message)
            elif remote_card is None:
                if _changed_fields(old_card, local_card):
                    reason = "remote-delete-local-edit"
                    record = {"copy_id": copy_id, "reason": reason, "base": old_card, "local": local_card, "remote": None}
                    if not _save_sync_conflicts(user_id, [record]):
                        return False, "Could not save the recovery record for a removed card."
                    conflict_count += 1
            elif _changed_fields(old_card, local_card):
                ok, message = _conditional_write_copy(collection_url, user_id, token, copy_id, base=old_card, local=local_card)
                if not ok:
                    return False, f"Failed to update a card in Firebase: {message}"
                conflict_count += int(message == "same-field-edit")

        for copy_id, card in local.items():
            if copy_id not in base and copy_id not in remote_all and copy_id not in remote_deleted:
                ok, message = _conditional_write_copy(collection_url, user_id, token, copy_id, local=card, add=True)
                if not ok:
                    return False, f"Failed to add a card to Firebase: {message}"

    final_data, err = make_firebase_request(collection_url, method="GET", token=token)
    if err:
        return False, f"Failed to confirm Firebase data: {err}"
    final_remote, _ = _remote_cards(final_data)
    merged_collection = sorted(final_remote.values(), key=lambda c: (int(c.get("Page", 1) or 1), int(c.get("Slot", 1) or 1), str(c.get("Name", ""))))
    if not save_collection(merged_collection, user_id=user_id):
        return False, "Firebase accepted the changes, but the local CSV could not be saved. Retry sync to recover using the same Copy IDs."
    save_sync_state(merged_collection, user_id=user_id)
    if conflict_count:
        return True, f"Synced {len(merged_collection)} cards. {conflict_count} conflict(s) were saved for recovery in this account's sync_conflicts.json."
    return True, f"Successfully synced {len(merged_collection)} cards with Firebase."

def push_username_to_firebase(config, username):
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    user_id = config.get("firebase_user_id")
    token = config.get("firebase_id_token")
    if not db_url or not user_id or not token:
        return False
        
    path_url = f"{db_url.rstrip('/')}/users/{user_id}/username.json"
    _, err = make_firebase_request(path_url, method="PUT", data=username, token=token)
    return err is None

def fetch_username_from_firebase(config):
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    user_id = config.get("firebase_user_id")
    token = config.get("firebase_id_token")
    if not db_url or not user_id or not token:
        return None
        
    path_url = f"{db_url.rstrip('/')}/users/{user_id}/username.json"
    data, err = make_firebase_request(path_url, method="GET", token=token)
    if err or data is None:
        return None
    return str(data)

def push_profile_to_firebase(config):
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    user_id = config.get("firebase_user_id")
    token = config.get("firebase_id_token")
    if not db_url or not user_id or not token:
        return False

    # Read local profile image base64 if it exists and source is upload or base64
    profile_image_base64 = ""
    if config.get("profile_picture_source") in ["upload", "base64"]:
        filepath = os.path.join(DATA_DIR, "profile_image.png")
        if os.path.exists(filepath):
            try:
                import base64
                with open(filepath, "rb") as f:
                    profile_image_base64 = base64.b64encode(f.read()).decode("utf-8")
            except Exception:
                pass

    profile_data = {
        "username": config.get("username", "Trainer"),
        "profile_picture_source": config.get("profile_picture_source", "pokemon"),
        "profile_featured_dex": int(config.get("profile_featured_dex", 25)),
        "profile_image_url": config.get("profile_image_url", ""),
        "profile_image_base64": profile_image_base64
    }    
    # Push username separately for backwards compatibility
    path_username = f"{db_url.rstrip('/')}/users/{user_id}/username.json"
    make_firebase_request(path_username, method="PUT", data=config.get("username", "Trainer"), token=token)

    path_profile = f"{db_url.rstrip('/')}/users/{user_id}/profile.json"
    _, err = make_firebase_request(path_profile, method="PUT", data=profile_data, token=token)
    return err is None

def fetch_profile_from_firebase(config):
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    user_id = config.get("firebase_user_id")
    token = config.get("firebase_id_token")
    if not db_url or not user_id or not token:
        return None
        
    path_profile = f"{db_url.rstrip('/')}/users/{user_id}/profile.json"
    data, err = make_firebase_request(path_profile, method="GET", token=token)
    if err or not isinstance(data, dict):
        return None
    return data


# ==================== CORE CALCULATIONS ====================

def get_slot_coordinates(abs_index, rows, cols):
    slots_per_page = rows * cols
    page = (abs_index - 1) // slots_per_page + 1
    slot = (abs_index - 1) % slots_per_page + 1
    return page, slot

def get_abs_index(page, slot, rows, cols):
    slots_per_page = rows * cols
    return (page - 1) * slots_per_page + slot

def find_next_sequential_slot(collection, rows, cols):
    occupied = set()
    for card in collection:
        p = card["Page"]
        s = card["Slot"]
        occupied.add(get_abs_index(p, s, rows, cols))
        
    idx = 1
    while idx in occupied:
        idx += 1
    return idx

def print_page(page, binder_map, rows, cols):
    cell_width = 24  # Width of each pocket cell
    content_width = cell_width - 2  # Internal printable area
    
    horizontal_line = "+" + ("-" * cell_width + "+") * cols
    
    print(f"\n====================== BINDER PAGE {page} ======================")
    print(horizontal_line)
    
    for r in range(rows):
        row_str1 = ""
        row_str2 = ""
        row_str3 = ""
        
        for c in range(cols):
            slot_num = r * cols + c + 1
            cards = binder_map.get((page, slot_num), [])
            
            # Line 1: Slot label
            slot_label = f"Slot {slot_num}"
            padded_slot = f"{slot_label:<{content_width}}"
            row_str1 += f"| {padded_slot} "
            
            # Line 2: Card name and dex number
            if cards:
                card = cards[0]
                name = card["Name"].capitalize()
                dex_num = card["Dex Number"]
                
                count = len(cards)
                suffix = f" (x{count})" if count > 1 else ""
                
                name_str = f"{name} (#{dex_num}){suffix}" if dex_num else f"{name}{suffix}"
                if len(name_str) > content_width:
                    name_str = name_str[:content_width - 3] + "..."
                padded_name = f"{name_str:<{content_width}}"
                row_str2 += f"| \033[92m{padded_name}\033[0m "  # Green for occupied
            else:
                padded_empty = f"{'[Empty]':<{content_width}}"
                row_str2 += f"| \033[90m{padded_empty}\033[0m "  # Gray for empty
                
            # Line 3: Notes or duplicates description
            if cards:
                if len(cards) > 1:
                    notes_str = f"{len(cards)} cards stacked"
                else:
                    notes_str = cards[0]["Notes"] or ""
                
                if len(notes_str) > content_width:
                    notes_str = notes_str[:content_width - 3] + "..."
                padded_notes = f"{notes_str:<{content_width}}"
                row_str3 += f"| \033[93m{padded_notes}\033[0m "  # Yellow for notes
            else:
                padded_space = f"{'':<{content_width}}"
                row_str3 += f"| {padded_space} "
                
        row_str1 += "|"
        row_str2 += "|"
        row_str3 += "|"
        
        print(row_str1)
        print(row_str2)
        print(row_str3)
        print(horizontal_line)

def view_binder(collection, rows, cols):
    if not collection:
        print(f"\n{CLR_INFO}[i] Your binder is empty. Add some cards first!{CLR_RESET}")
        input("\nPress Enter to return to menu...")
        return
        
    binder_map = {}
    max_page = 1
    for card in collection:
        p, s = card["Page"], card["Slot"]
        if (p, s) not in binder_map:
            binder_map[(p, s)] = []
        binder_map[(p, s)].append(card)
        if p > max_page:
            max_page = p
            
    page = 1
    while True:
        clear_screen()
        print_page(page, binder_map, rows, cols)
        print(f"\nViewing Page {page} of {max_page} (Total cards: {len(collection)})")
        print("-" * 40)
        print("Navigation: ")
        print("  [N]ext Page | [P]revious Page | [Jump to Page Number] | [Q]uit to Menu")
        choice = input("\nChoose an option: ").strip().lower()
        
        if choice == 'n':
            if page < max_page:
                page += 1
            else:
                input("\nYou are already on the last page. Press Enter...")
        elif choice == 'p':
            if page > 1:
                page -= 1
            else:
                input("\nYou are already on the first page. Press Enter...")
        elif choice == 'q' or choice == '':
            break
        elif choice.isdigit():
            p_num = int(choice)
            if 1 <= p_num <= max_page + 10:  # Allow browsing a bit past the max page
                page = p_num
            else:
                print(f"{CLR_FAIL}Please choose a valid page between 1 and {max_page + 10}.{CLR_RESET}")
                input("Press Enter...")

def add_card_interactive(db, config):
    clear_screen()
    print(f"{CLR_HEADER}=== ADD NEW POKÉMON CARD ==={CLR_RESET}")
    print(f"Current binder layout: {config['rows']}x{config['cols']} ({config['rows']*config['cols']} pockets)")
    print(f"Default sorting mode: {'National Dex Order' if config['mode'] == 'dex' else 'Sequential/Fill Order'}")
    print("-" * 40)
    
    query = input("Enter Pokémon name or National Dex ID (or press Enter to cancel): ").strip()
    if not query:
        return
        
    dex_id, name = find_pokemon(query, db)
    
    if dex_id == "multiple":
        print(f"\n{CLR_WARNING}[?] Multiple matches found:{CLR_RESET}")
        for i, match in enumerate(name, 1):
            print(f"  {i}. {match.capitalize()} (#{_pokemon_cache_id(db[match])})")
        
        choice = input("\nChoose a number (or 'c' to cancel): ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(name):
            selected_name = name[int(choice) - 1]
            dex_id = _pokemon_cache_id(db[selected_name])
            name = selected_name
        else:
            print("Cancelled.")
            input("\nPress Enter to return...")
            return
    elif not dex_id:
        print(f"\n{CLR_WARNING}[!] Pokémon not found in database.{CLR_RESET}")
        use_custom = input("Do you want to add this as a custom card anyway? (y/n): ").strip().lower()
        if use_custom == 'y':
            name = query
            dex_id = 0  # No Dex number
        else:
            return

    # Calculate default page and slot
    collection = load_collection()
    rows, cols = config["rows"], config["cols"]
    
    if config["mode"] == "dex" and dex_id > 0:
        calc_page, calc_slot = get_slot_coordinates(dex_id, rows, cols)
        position_type = "National Dex position"
    else:
        # Sequential mode, or it's a custom card with no dex ID
        next_idx = find_next_sequential_slot(collection, rows, cols)
        calc_page, calc_slot = get_slot_coordinates(next_idx, rows, cols)
        position_type = "First available binder slot"
        
    print(f"\nIdentified: {CLR_SUCCESS}{name.capitalize()}{CLR_RESET}" + (f" (National Dex #{dex_id})" if dex_id else ""))
    print(f"Suggested Position ({position_type}):")
    print(f"  -> {CLR_INFO}Page {calc_page}, Slot {calc_slot}{CLR_RESET}")
    
    # Check if slot is already occupied
    occupied_cards = [c for c in collection if c["Page"] == calc_page and c["Slot"] == calc_slot]
    if occupied_cards:
        print(f"\n{CLR_WARNING}[!] Warning: Page {calc_page}, Slot {calc_slot} is currently occupied by:{CLR_RESET}")
        for c in occupied_cards:
            print(f"    - {c['Name'].capitalize()}" + (f" (#{c['Dex Number']})" if c['Dex Number'] else "") + (f" [{c['Notes']}]" if c['Notes'] else ""))
            
    # Position confirmation or override
    pos_choice = input(f"\nPlace in Page {calc_page}, Slot {calc_slot}? (Y/n/Manual override): ").strip().lower()
    
    page, slot = calc_page, calc_slot
    if pos_choice == 'n':
        print("Cancelled adding card.")
        input("\nPress Enter to return...")
        return
    elif pos_choice == 'm' or pos_choice == 'manual':
        try:
            manual_page = int(input("Enter Page number: "))
            manual_slot = int(input(f"Enter Slot number (1 to {rows*cols}): "))
            if manual_page < 1 or manual_slot < 1 or manual_slot > rows*cols:
                raise ValueError
            page, slot = manual_page, manual_slot
        except ValueError:
            print(f"{CLR_FAIL}[!] Invalid inputs. Reverting to suggested Page {calc_page}, Slot {calc_slot}.{CLR_RESET}")
            page, slot = calc_page, calc_slot
            
    # Gather other metadata
    print("\nCard details:")
    print("  1. NM (Near Mint)")
    print("  2. LP (Lightly Played)")
    print("  3. MP (Moderately Played)")
    print("  4. HP (Heavily Played)")
    cond_input = input("Choose Condition (1-4, default NM): ").strip()
    conditions = {"1": "NM", "2": "LP", "3": "MP", "4": "HP"}
    condition = conditions.get(cond_input, "NM")
    
    notes = input("Enter notes (e.g. Holo, Reverse Holo, Base Set, 1st Ed - leave blank for none): ").strip()
    
    # Create card entry
    new_card = {
        "Page": page,
        "Slot": slot,
        "Dex Number": dex_id if dex_id > 0 else "",
        "Name": name.lower(),
        "Condition": condition,
        "Notes": notes,
        "Date Added": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    }
    
    # Save card locally
    if add_card_to_csv(new_card):
        print(f"\n{CLR_SUCCESS}[+] Added {name.capitalize()} to Page {page}, Slot {slot}! Saved to '{DATA_FILE}'.{CLR_RESET}")
        
        # Reload collection to get the most updated version for sync
        updated_collection = load_collection()

        # Sync with Google Sheets if enabled
        if config.get("gsheet_enabled"):
            print(f"{CLR_INFO}[i] Synchronizing changes to Google Sheets...{CLR_RESET}")
            success, msg = sync_with_google_sheets(config, updated_collection)
            if success:
                print(f"{CLR_SUCCESS}[+] Google Sheets synced successfully!{CLR_RESET}")
            else:
                print(f"{CLR_WARNING}[!] Google Sheets sync failed: {msg}{CLR_RESET}")
        
        # Sync with Firebase if enabled
        if config.get("firebase_enabled"):
            print(f"{CLR_INFO}[i] Synchronizing changes to Firebase...{CLR_RESET}")
            success, msg = sync_with_firebase(config, updated_collection)
            if success:
                print(f"{CLR_SUCCESS}[+] Firebase sync completed!{CLR_RESET}")
            else:
                print(f"{CLR_WARNING}[!] Firebase sync failed: {msg}{CLR_RESET}")
    else:
        print(f"\n{CLR_FAIL}[!] Failed to save card.{CLR_RESET}")
        
    input("\nPress Enter to continue...")

def search_collection(db, config):
    clear_screen()
    print(f"{CLR_HEADER}=== SEARCH BINDER COLLECTION ==={CLR_RESET}")
    query = input("Search by Pokémon Name, Dex ID, or Notes: ").strip().lower()
    if not query:
        return
        
    collection = load_collection()
    results = []
    
    for idx, card in enumerate(collection):
        name_match = query in card["Name"]
        dex_match = query.isdigit() and str(card["Dex Number"]) == query
        notes_match = card["Notes"] and query in card["Notes"].lower()
        
        if name_match or dex_match or notes_match:
            results.append((idx, card))
            
    if not results:
        print(f"\n{CLR_WARNING}No cards matching '{query}' found in your binder.{CLR_RESET}")
    else:
        print(f"\n{CLR_SUCCESS}Found {len(results)} matching card(s):{CLR_RESET}")
        print("-" * 75)
        print(f"{'No.':<4} | {'Name':<15} | {'Dex':<5} | {'Page':<5} | {'Slot':<5} | {'Condition':<10} | {'Notes':<15}")
        print("-" * 75)
        for idx, (original_idx, card) in enumerate(results, 1):
            print(f"{idx:<4} | {card['Name'].capitalize():<15} | {card['Dex Number'] if card['Dex Number'] else '-':<5} | {card['Page']:<5} | {card['Slot']:<5} | {card['Condition']:<10} | {card['Notes'] if card['Notes'] else '-':<15}")
        print("-" * 75)
        
        remove_choice = input("\nDo you want to REMOVE any of these cards? Enter result No. (or press Enter to cancel): ").strip()
        if remove_choice.isdigit():
            r_idx = int(remove_choice) - 1
            if 0 <= r_idx < len(results):
                orig_index, card_to_remove = results[r_idx]
                confirm = input(f"Are you sure you want to remove {card_to_remove['Name'].capitalize()} from Page {card_to_remove['Page']}, Slot {card_to_remove['Slot']}? (y/n): ").strip().lower()
                if confirm == 'y':
                    collection.pop(orig_index)
                    if save_collection(collection):
                        print(f"\n{CLR_SUCCESS}[+] Card successfully removed locally!{CLR_RESET}")
                        if config.get("gsheet_enabled"):
                            print(f"{CLR_INFO}[i] Synchronizing changes to Google Sheets...{CLR_RESET}")
                            success, msg = sync_with_google_sheets(config, collection)
                            if success:
                                print(f"{CLR_SUCCESS}[+] Google Sheets sync completed!{CLR_RESET}")
                            else:
                                print(f"{CLR_WARNING}[!] Google Sheets sync failed: {msg}{CLR_RESET}")
                        if config.get("firebase_enabled"):
                            print(f"{CLR_INFO}[i] Synchronizing changes to Firebase...{CLR_RESET}")
                            success, msg = sync_with_firebase(config, collection)
                            if success:
                                print(f"{CLR_SUCCESS}[+] Firebase sync completed!{CLR_RESET}")
                            else:
                                print(f"{CLR_WARNING}[!] Firebase sync failed: {msg}{CLR_RESET}")
                    else:
                        print(f"\n{CLR_FAIL}[!] Failed to update collection.{CLR_RESET}")
                else:
                    print("\nDeletion cancelled.")
            else:
                print("\nInvalid choice.")
        
    input("\nPress Enter to return...")

def configure_gsheet(config):
    while True:
        clear_screen()
        gsheet_status = "Enabled" if config.get("gsheet_enabled") else "Disabled"
        sheet_name = config.get("gsheet_name") or "[Not Set]"
        
        print(f"{CLR_HEADER}=== GOOGLE SHEETS INTEGRATION ==={CLR_RESET}")
        print(f"Status              : {CLR_INFO}{gsheet_status}{CLR_RESET}")
        print(f"Spreadsheet Name    : {CLR_INFO}{sheet_name}{CLR_RESET}")
        print("-" * 40)
        print("1. Toggle Enable/Disable")
        print("2. Set Spreadsheet Name")
        print("3. View Setup Setup Guide (Step-by-Step)")
        print("4. Sync Local Collection to Google Sheets Now")
        print("5. Back to Settings")
        print("-" * 40)
        
        choice = input("Choose an option: ").strip()
        
        if choice == '1':
            config["gsheet_enabled"] = not config.get("gsheet_enabled", False)
            save_config(config)
            status = "enabled" if config["gsheet_enabled"] else "disabled"
            print(f"\n{CLR_SUCCESS}[+] Google Sheets integration is now {status}.{CLR_RESET}")
            if config["gsheet_enabled"] and not config.get("gsheet_name"):
                print(f"{CLR_WARNING}[!] Note: You must set a Spreadsheet Name (Option 2) for integration to work!{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '2':
            name = input("\nEnter Google Spreadsheet Name exactly as it appears in Google Drive: ").strip()
            if name:
                config["gsheet_name"] = name
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Spreadsheet name set to: '{name}'{CLR_RESET}")
            else:
                print("\nSpreadsheet name cannot be empty.")
            input("\nPress Enter to continue...")
        elif choice == '3':
            clear_screen()
            print_google_sheets_setup_guide()
            input("\nPress Enter to return...")
        elif choice == '4':
            print(f"\n{CLR_INFO}[i] Performing full sync...{CLR_RESET}")
            collection = load_collection()
            success, msg = sync_with_google_sheets(config, collection)
            if success:
                print(f"\n{CLR_SUCCESS}[+] {msg}{CLR_RESET}")
            else:
                print(f"\n{CLR_FAIL}[!] Sync failed:\n    {msg}{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '5' or choice == '':
            break

def configure_firebase(config):
    while True:
        clear_screen()
        firebase_status = "Enabled" if config.get("firebase_enabled") else "Disabled"
        db_url = config.get("firebase_db_url") or "[Not Set]"
        auth_method = config.get("firebase_auth_method") or "secret"
        user_id = config.get("firebase_user_id") or "[Not Set]"
        
        print(f"{CLR_HEADER}=== FIREBASE SYNC SETTINGS ==={CLR_RESET}")
        print(f"Status           : {CLR_INFO}{firebase_status}{CLR_RESET}")
        print(f"Database URL     : {CLR_INFO}{db_url}{CLR_RESET}")
        print(f"Auth Method      : {CLR_INFO}{auth_method}{CLR_RESET}")
        print(f"User ID (Path)   : {CLR_INFO}{user_id}{CLR_RESET}")
        print("-" * 40)
        print("1. Toggle Enable/Disable")
        print("2. Set Database URL")
        print("3. Set Auth Method (secret / service_account / none)")
        print("4. Set Database Secret (if method is secret)")
        print("5. Set Custom User ID")
        print("6. Sync Local Collection to Firebase Now")
        print("7. Back to Settings")
        print("-" * 40)
        
        choice = input("Choose an option: ").strip()
        if choice == '1':
            config["firebase_enabled"] = not config.get("firebase_enabled", False)
            save_config(config)
            status = "enabled" if config["firebase_enabled"] else "disabled"
            print(f"\n{CLR_SUCCESS}[+] Firebase integration is now {status}.{CLR_RESET}")
            if config["firebase_enabled"] and not config.get("firebase_db_url"):
                print(f"{CLR_WARNING}[!] Note: You must set a Firebase Database URL (Option 2) for it to work!{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '2':
            url = input("\nEnter Firebase Database URL: ").strip()
            if url:
                config["firebase_db_url"] = url
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Database URL set to: '{url}'{CLR_RESET}")
            else:
                print("\nURL cannot be empty.")
            input("\nPress Enter to continue...")
        elif choice == '3':
            print("\nSelect Auth Method:")
            print("  1. Database Secret (Token)")
            print("  2. Service Account JSON (uses credentials.json)")
            print("  3. None (Public read/write)")
            m = input("Choose (1-3): ").strip()
            if m == '1':
                config["firebase_auth_method"] = "secret"
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Auth method set to: Database Secret{CLR_RESET}")
            elif m == '2':
                config["firebase_auth_method"] = "service_account"
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Auth method set to: Service Account JSON{CLR_RESET}")
            elif m == '3':
                config["firebase_auth_method"] = "none"
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Auth method set to: None (Public){CLR_RESET}")
            else:
                print("\nInvalid choice.")
            input("\nPress Enter to continue...")
        elif choice == '4':
            secret = input("\nEnter Firebase Database Secret: ").strip()
            config["firebase_secret"] = secret
            save_config(config)
            print(f"\n{CLR_SUCCESS}[+] Database Secret saved.{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '5':
            uid = input("\nEnter unique User ID: ").strip().replace(" ", "_")
            if uid:
                config["firebase_user_id"] = uid
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] User ID set to: '{uid}'{CLR_RESET}")
            else:
                print("\nUser ID cannot be empty.")
            input("\nPress Enter to continue...")
        elif choice == '6':
            print(f"\n{CLR_INFO}[i] Synchronizing collection with Firebase...{CLR_RESET}")
            collection = load_collection()
            success, msg = sync_with_firebase(config, collection)
            if success:
                print(f"\n{CLR_SUCCESS}[+] {msg}{CLR_RESET}")
            else:
                print(f"\n{CLR_FAIL}[!] Sync failed:\n    {msg}{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '7' or choice == '':
            break

def configure_binder(config):
    while True:
        clear_screen()
        print(f"{CLR_HEADER}=== BINDER SETTINGS ==={CLR_RESET}")
        print(f"1. Binder Grid Size   : {config['rows']} rows x {config['cols']} columns ({config['rows']*config['cols']} pockets)")
        print(f"2. Sorting Mode       : {'National Dex Order' if config['mode'] == 'dex' else 'Sequential / Fill Order'}")
        
        gsheet_status = "Enabled" if config.get("gsheet_enabled") else "Disabled"
        sheet_name = config.get("gsheet_name") or "[Not Set]"
        print(f"3. Google Sheets Settings: {gsheet_status} (Sheet: '{sheet_name}')")
        
        firebase_status = "Enabled" if config.get("firebase_enabled") else "Disabled"
        db_url = config.get("firebase_db_url") or "[Not Set]"
        print(f"4. Firebase Settings     : {firebase_status} (URL: '{db_url}')")
        print("5. Back to Main Menu")
        print("-" * 40)
        
        choice = input("Select setting to modify: ").strip()
        
        if choice == '1':
            try:
                r = int(input("\nEnter number of rows (e.g. 3): "))
                c = int(input("Enter number of columns (e.g. 3): "))
                if r < 1 or c < 1:
                    raise ValueError
                config["rows"] = r
                config["cols"] = c
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Grid set to {r}x{c} ({r*c} pockets per page)!{CLR_RESET}")
                print(f"{CLR_WARNING}[!] Note: Existing card slots remain, but visual display calculations will adapt.{CLR_RESET}")
            except ValueError:
                print(f"\n{CLR_FAIL}[!] Invalid inputs. Layout not changed.{CLR_RESET}")
            input("\nPress Enter to continue...")
        elif choice == '2':
            print("\nChoose Sorting Mode:")
            print("  1. National Dex Order (Bulbasaur is slot 1, Charmander is slot 4, etc.)")
            print("  2. Sequential / Fill Order (Place cards in the first empty pocket, filling left-to-right, page-by-page)")
            m_choice = input("Choose option (1-2): ").strip()
            if m_choice == '1':
                config["mode"] = "dex"
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Sorting Mode set to: National Dex Order{CLR_RESET}")
            elif m_choice == '2':
                config["mode"] = "sequential"
                save_config(config)
                print(f"\n{CLR_SUCCESS}[+] Sorting Mode set to: Sequential / Fill Order{CLR_RESET}")
            else:
                print("\nInvalid selection.")
            input("\nPress Enter to continue...")
        elif choice == '3':
            configure_gsheet(config)
        elif choice == '4':
            configure_firebase(config)
        elif choice == '5' or choice == '':
            break

def main():
    config = load_config()
    db = load_pokemon_database()
    
    # Ensure database was loaded properly
    if not db:
        print(f"{CLR_FAIL}[!] Could not load Pokémon database. Exiting.{CLR_RESET}")
        return

    while True:
        clear_screen()
        collection = load_collection()
        
        sync_modes = []
        if config.get("gsheet_enabled"):
            sync_modes.append(f"Sheets ('{config.get('gsheet_name')}')")
        if config.get("firebase_enabled"):
            sync_modes.append(f"Firebase ('{config.get('firebase_user_id')}')")
        sync_status = " + ".join(sync_modes) if sync_modes else "Local Only"
        
        print(f"{CLR_HEADER}=================================================={CLR_RESET}")
        print(f"{CLR_HEADER}             POKÉMON BINDER MANAGER               {CLR_RESET}")
        print(f"{CLR_HEADER}=================================================={CLR_RESET}")
        print(f" Binder Size : {CLR_INFO}{config['rows']}x{config['cols']} ({config['rows']*config['cols']} pockets per page){CLR_RESET}")
        print(f" Sort Mode   : {CLR_INFO}{'National Dex' if config['mode'] == 'dex' else 'Sequential'}{CLR_RESET}")
        print(f" Sync Mode   : {CLR_INFO}{sync_status}{CLR_RESET}")
        print(f" Total Cards : {CLR_SUCCESS}{len(collection)} saved in binder{CLR_RESET}")
        print("-" * 50)
        print(f" [{CLR_SUCCESS}1{CLR_RESET}] Add Card to Binder")
        print(f" [{CLR_SUCCESS}2{CLR_RESET}] View Binder Pages (Visual Grid)")
        print(f" [{CLR_SUCCESS}3{CLR_RESET}] Search & Remove Cards")
        print(f" [{CLR_SUCCESS}4{CLR_RESET}] Settings (Grid size, mode, Google Sheets, Firebase)")
        print(f" [{CLR_SUCCESS}5{CLR_RESET}] Exit")
        print("-" * 50)
        
        choice = input("Choose an option: ").strip()
        
        if choice == '1':
            add_card_interactive(db, config)
        elif choice == '2':
            view_binder(collection, config["rows"], config["cols"])
        elif choice == '3':
            search_collection(db, config)
        elif choice == '4':
            configure_binder(config)
        elif choice == '5':
            print(f"\nThank you for using Pokémon Binder Manager! Happy collecting! :)")
            break
        else:
            print(f"\n{CLR_FAIL}[!] Invalid option. Please try again.{CLR_RESET}")
            input("Press Enter...")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nExiting... Goodbye!")
        sys.exit(0)
