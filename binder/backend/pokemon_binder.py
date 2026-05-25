import os
import sys
import csv
import json
import urllib.request
import urllib.error
import shutil
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

def load_sync_state():
    if os.path.exists(SYNC_STATE_FILE):
        try:
            with open(SYNC_STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def save_sync_state(state):
    try:
        with open(SYNC_STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=4)
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
    "firebase_remember_password": True,
    "username": "",
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

def load_pokemon_database():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass

    print(f"{CLR_INFO}[i] Initializing Pokémon species database with types...{CLR_RESET}")
    print("    (Fetching from high-speed mirror to enable offline type backgrounds.)")

    try:
        # Use a more comprehensive data source that includes types to avoid 1025 API calls
        url = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon.csv"
        # Since CSV parsing of the full database is complex, let's use a simpler JSON mirror if possible
        # Or just fetch the species list and assume types for now, but better to have a proper map.
        # Fallback to a pre-generated map for common types
        db = get_gen1_fallback() # Start with gen1

        # In a real scenario, we'd fetch a full mapping. For now, let's stick to gen1 + expanded logic in JS
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(db, f, indent=4)
        return db
    except Exception as e:
        print(f"{CLR_WARNING}[!] Warning: Could not initialize database ({e}).{CLR_RESET}")
        return get_gen1_fallback()
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
            return int(query), db[query]
        else:
            return db[query], query
            
    # Try partial name matching in cached db
    matches = [name for name in db.keys() if not name.isdigit() and query in name]
    if len(matches) == 1:
        return db[matches[0]], matches[0]
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
            db[name] = dex_id
            db[str(dex_id)] = name
            with open(CACHE_FILE, "w", encoding="utf-8") as f:
                json.dump(db, f, indent=4)
            return dex_id, name
    except Exception:
        pass
        
    return None, None

def load_config():
    config_loaded = False
    config = DEFAULT_CONFIG.copy()
    
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
            
    # Generate unique firebase_user_id if missing or empty
    if not config.get("firebase_user_id"):
        import uuid
        owner = config.get("cover_owner", "user").strip().lower().replace(" ", "_")
        if not owner:
            owner = "user"
        unique_suffix = uuid.uuid4().hex[:6]
        config["firebase_user_id"] = f"{owner}_{unique_suffix}"
        save_config(config)
    elif not config_loaded:
        # If it was not loaded from disk, save default config + generated user_id
        save_config(config)
        
    return config

def save_config(config):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4, ensure_ascii=False)
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error saving configuration: {e}{CLR_RESET}")

def load_collection():
    collection = []
    migration_needed = False
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r", newline="", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                # If 'Type' is missing from the headers, we must trigger migration
                has_type_column = "Type" in reader.fieldnames if reader.fieldnames else False
                if not has_type_column:
                    migration_needed = True
                
                for row in reader:
                    # Parse numerical fields safely
                    row["Page"] = int(row["Page"])
                    row["Slot"] = int(row["Slot"])
                    row["Dex Number"] = int(row["Dex Number"]) if row["Dex Number"] else 0
                    
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
                save_collection(collection)
        except Exception as e:
            print(f"{CLR_FAIL}[!] Error reading collection CSV: {e}{CLR_RESET}")
    return collection

def save_collection(collection):
    fieldnames = ["Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added"]
    try:
        with open(DATA_FILE, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for card in collection:
                row = {k: card.get(k, "") for k in fieldnames}
                writer.writerow(row)
        return True
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error saving collection: {e}{CLR_RESET}")
        return False

def add_card_to_csv(card):
    fieldnames = ["Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added"]
    file_exists = os.path.exists(DATA_FILE)
    try:
        with open(DATA_FILE, "a", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            if not file_exists:
                writer.writeheader()
            row = {k: card.get(k, "") for k in fieldnames}
            writer.writerow(row)
        return True
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error writing to CSV file: {e}{CLR_RESET}")
        return False

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
            worksheet = sh.add_worksheet(title="Binder Collection", rows="1000", cols="8")
            
        # Write headers
        headers = ["Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added"]
        
        # Assemble rows
        rows_to_write = []
        for card in collection:
            rows_to_write.append([
                card["Page"],
                card["Slot"],
                card["Dex Number"] if card["Dex Number"] else "",
                card["Name"],
                card.get("Type", "Normal"),
                card["Condition"],
                card["Notes"] or "",
                card["Date Added"]
            ])
            
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

def make_firebase_request(url, method="GET", data=None, token=None):
    import urllib.request
    import urllib.error
    import json
    
    headers = {
        "Content-Type": "application/json"
    }
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
            return json.loads(res_content) if res_content else None, None
    except urllib.error.HTTPError as e:
        try:
            err_msg = e.read().decode("utf-8")
            err_data = json.loads(err_msg)
            return None, f"HTTP Error {e.code}: {err_data.get('error', err_msg)}"
        except Exception:
            return None, f"HTTP Error {e.code}: {e.reason}"
    except Exception as e:
        return None, f"Connection error: {e}"

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
        if not config.get("firebase_remember_password", True):
            config["firebase_password"] = ""
        save_config(config)
        return True, "Login successful!"
    return False, "Invalid response from authentication server."

def firebase_sign_up(config, email, password):
    api_key = FIREBASE_API_KEY or config.get("firebase_api_key")
    if not api_key:
        return False, "Missing Firebase Web API Key in settings."
        
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
        if config.get("firebase_remember_password", True):
            config["firebase_password"] = password
        config["firebase_id_token"] = res_data["idToken"]
        config["firebase_user_id"] = res_data["localId"]
        save_config(config)
        return True, "Registration successful!"
    return False, "Invalid response from registration server."

def firebase_logout(config):
    config["firebase_enabled"] = False
    config["firebase_email"] = ""
    config["firebase_password"] = ""
    config["firebase_id_token"] = ""
    config["firebase_user_id"] = ""
    save_config(config)
    
    # Remove local sync state to force union merge on next login
    if os.path.exists(SYNC_STATE_FILE):
        try:
            os.remove(SYNC_STATE_FILE)
        except Exception:
            pass
    return True, "Disconnected from Firebase and cleared sync state."

def sync_with_firebase(config, collection):
    if not config.get("firebase_enabled"):
        return False, "Firebase sync is currently disabled."
        
    db_url = FIREBASE_DB_URL or config.get("firebase_db_url")
    if not db_url:
        return False, "Firebase Database URL is not configured."
        
    auth_method = config.get("firebase_auth_method", "secret")
    if FIREBASE_API_KEY and FIREBASE_DB_URL:
        auth_method = "auth"
        
    secret = config.get("firebase_secret", "")
    user_id = config.get("firebase_user_id", "default_user")
    
    # Clean database URL
    db_url = db_url.strip()
    if not db_url.startswith("http"):
        db_url = "https://" + db_url
        
    # Append path
    path_url = f"{db_url.rstrip('/')}/users/{user_id}/collection.json"
    
    token = None
    if auth_method == "service_account":
        token, err = get_firebase_oauth_token()
        if err:
            return False, f"Firebase auth failed: {err}"
    elif auth_method == "secret" and secret:
        import urllib.parse
        path_url += f"?auth={urllib.parse.quote(secret)}"
    elif auth_method == "auth":
        token = config.get("firebase_id_token")
        if not token:
            # Try to auto-login to refresh token
            success, login_msg = firebase_sign_in(config)
            if success:
                token = config.get("firebase_id_token")
                # User ID might have changed/loaded
                user_id = config.get("firebase_user_id", "default_user")
                path_url = f"{db_url.rstrip('/')}/users/{user_id}/collection.json"
            else:
                return False, f"Authentication required: {login_msg}"
        
    # 1. Fetch current remote collection from Firebase
    remote_data, err = make_firebase_request(path_url, method="GET", token=token)
    
    # Handle automatic session refresh on 401 Unauthorized
    if err and "HTTP Error 401" in err and auth_method == "auth":
        success, login_msg = firebase_sign_in(config)
        if success:
            token = config.get("firebase_id_token")
            user_id = config.get("firebase_user_id", "default_user")
            path_url = f"{db_url.rstrip('/')}/users/{user_id}/collection.json"
            remote_data, err = make_firebase_request(path_url, method="GET", token=token)
        else:
            return False, f"Session expired and auto-login failed: {login_msg}"
            
    if err:
        return False, f"Failed to retrieve Firebase data: {err}"
        
    # 1.5 Update Profile / Username from cloud
    cloud_profile = fetch_profile_from_firebase(config)
    if cloud_profile:
        config["username"] = cloud_profile.get("username", config.get("username", "Dr4g0n"))
        config["profile_picture_source"] = cloud_profile.get("profile_picture_source", config.get("profile_picture_source", "pokemon"))
        config["profile_featured_dex"] = int(cloud_profile.get("profile_featured_dex", config.get("profile_featured_dex", 25)))
        config["profile_image_url"] = cloud_profile.get("profile_image_url", config.get("profile_image_url", ""))
        
        # Save custom uploaded image locally if present in cloud
        base64_img = cloud_profile.get("profile_image_base64")
        if base64_img and config.get("profile_picture_source") in ["upload", "base64"]:
            try:
                import base64 as b64
                filepath = os.path.join(DATA_DIR, "profile_image.png")
                with open(filepath, "wb") as f:
                    f.write(b64.b64decode(base64_img))
                config["profile_image_path"] = "profile_image.png"
                # Map cloud source name to PC internal name
                if config.get("profile_picture_source") == "base64":
                    config["profile_picture_source"] = "upload"
            except Exception:
                pass
        save_config(config)
    else:
        if not config.get("username"):
            cloud_username = fetch_username_from_firebase(config)
            if cloud_username:
                config["username"] = cloud_username
                save_config(config)


    remote_collection = []
    if remote_data:
        if isinstance(remote_data, list):
            remote_collection = [c for c in remote_data if c is not None]
        elif isinstance(remote_data, dict):
            remote_collection = list(remote_data.values())
            
        # Normalize remote collection rarities to match current schema
        for r_card in remote_collection:
            t_val = str(r_card.get("Type", "")).strip()
            t_val_lower = t_val.lower()
            if t_val == "IR" or t_val_lower == "ir" or t_val_lower == "ilustration rare":
                r_card["Type"] = "Illustration Rare"
            elif t_val == "SIR" or t_val_lower == "sir":
                r_card["Type"] = "Special Illustration Rare"
            elif t_val == "SR" or t_val_lower == "sr":
                r_card["Type"] = "Secret Rare"
            elif t_val == "HR" or t_val_lower == "hr":
                r_card["Type"] = "Hyper Rare"
            elif t_val == "Holo" or t_val_lower == "holo" or t_val_lower == "holofoil":
                r_card["Type"] = "Holofoil Rare"
            elif t_val_lower in ["common", "uncommon"]:
                r_card["Type"] = "Normal"
            
    # 2. Perform 3-way Merge
    last_sync = load_sync_state()
    
    # Debug log setup
    debug_log_path = os.path.join(DATA_DIR, "debug_sync.log")
    def log_debug(msg):
        with open(debug_log_path, "a", encoding="utf-8") as f:
            f.write(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")

    log_debug(f"Starting Sync. Local: {len(collection)}, Remote: {len(remote_collection)}, LastSync: {len(last_sync)}")

    # If no sync state exists, perform a union merge on first sync without establishing baseline from remote
    is_first_sync = not last_sync
    if is_first_sync:
        log_debug("First sync detected. Baseline is empty; union-merging local and remote collections non-destructively.")

    def to_int(v, default=0):
        if v is None: return default
        try:
            s = str(v).strip()
            if not s: return default
            return int(float(s))
        except (ValueError, TypeError):
            return default

    def get_card_identity(c):
        # Extremely robust identity tuple
        def safe_str(v):
            if v is None: return ""
            return str(v).lower().strip()

        return (
            to_int(c.get("Page"), 1),
            to_int(c.get("Slot"), 1),
            to_int(c.get("Dex Number"), 0),
            safe_str(c.get("Name")),
            safe_str(c.get("Type") or "Normal").lower(),
            safe_str(c.get("Condition") or "NM").upper(), # Normalize condition to uppercase
            safe_str(c.get("Notes"))
        )
        
    def group_by_id(coll):
        groups = {}
        for card in coll:
            key = get_card_identity(card)
            if key not in groups:
                groups[key] = []
            groups[key].append(card)
        return groups

    local_groups = group_by_id(collection)
    remote_groups = group_by_id(remote_collection)
    last_sync_groups = group_by_id(last_sync)
    
    all_keys = set(local_groups.keys()).union(set(remote_groups.keys())).union(set(last_sync_groups.keys()))
    
    merged_collection = []
    for key in all_keys:
        l_list = local_groups.get(key, [])
        r_list = remote_groups.get(key, [])
        s_list = last_sync_groups.get(key, [])
        
        # 3-way merge: base + local_delta + remote_delta (or union on first sync)
        if is_first_sync:
            final_count = max(len(l_list), len(r_list))
        else:
            l_delta = len(l_list) - len(s_list)
            r_delta = len(r_list) - len(s_list)
            final_count = max(0, len(s_list) + l_delta + r_delta)
        
        if final_count != len(l_list):
            log_debug(f"Merge Change for {key}: Local={len(l_list)} -> Final={final_count} (Remote={len(r_list)}, Base={len(s_list)})")
        
        for i in range(final_count):
            if i < len(l_list):
                merged_collection.append(l_list[i])
            elif i < len(r_list):
                card_to_add = r_list[i].copy()
                # Ensure types are correct for the newly added card
                card_to_add["Page"] = to_int(card_to_add.get("Page"), 1)
                card_to_add["Slot"] = to_int(card_to_add.get("Slot"), 1)
                card_to_add["Dex Number"] = to_int(card_to_add.get("Dex Number"), 0)
                merged_collection.append(card_to_add)
                
    # Sort merged collection
    merged_collection.sort(key=lambda c: (int(c.get("Page", 1)), int(c.get("Slot", 1)), str(c.get("Name", ""))))
    
    # 3. Push merged collection back to Firebase
    _, err = make_firebase_request(path_url, method="PUT", data=merged_collection, token=token)
    
    # Handle automatic session refresh on push if it expires
    if err and "HTTP Error 401" in err and auth_method == "auth":
        success, login_msg = firebase_sign_in(config)
        if success:
            token = config.get("firebase_id_token")
            user_id = config.get("firebase_user_id", "default_user")
            path_url = f"{db_url.rstrip('/')}/users/{user_id}/collection.json"
            _, err = make_firebase_request(path_url, method="PUT", data=merged_collection, token=token)
        else:
            return False, f"Session expired and auto-login failed: {login_msg}"
            
    if err:
        return False, f"Failed to push merged data to Firebase: {err}"
        
    # 4. Update local CSV and Sync State
    if save_collection(merged_collection):
        save_sync_state(merged_collection)
        return True, f"Successfully synced {len(merged_collection)} cards with Firebase!"
    else:
        return False, "Merged data pushed to Firebase, but failed to write locally to CSV."

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
        "username": config.get("username", "Dr4g0n"),
        "profile_picture_source": config.get("profile_picture_source", "pokemon"),
        "profile_featured_dex": int(config.get("profile_featured_dex", 25)),
        "profile_image_url": config.get("profile_image_url", ""),
        "profile_image_base64": profile_image_base64
    }    
    # Push username separately for backwards compatibility
    path_username = f"{db_url.rstrip('/')}/users/{user_id}/username.json"
    make_firebase_request(path_username, method="PUT", data=config.get("username", "Dr4g0n"), token=token)

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
            print(f"  {i}. {match.capitalize()} (#{db[match]})")
        
        choice = input("\nChoose a number (or 'c' to cancel): ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(name):
            selected_name = name[int(choice) - 1]
            dex_id = db[selected_name]
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
