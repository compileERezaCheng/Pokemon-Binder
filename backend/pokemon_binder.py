import os
import sys
import csv
import json
import urllib.request
import urllib.error
import shutil
from datetime import datetime

# Initialize ANSI color support on Windows
if sys.platform == 'win32':
    os.system('color')

# Configuration constants
DATA_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "data"))
CONFIG_FILE = os.path.join(DATA_DIR, "binder_config.json")
DATA_FILE = os.path.join(DATA_DIR, "pokemon_binder.csv")
CACHE_FILE = os.path.join(DATA_DIR, "pokemon_cache.json")

# Auto-create data directory if not exists
if not os.path.exists(DATA_DIR):
    try:
        os.makedirs(DATA_DIR)
    except Exception:
        pass

# Copy default templates if they do not exist
try:
    if not os.path.exists(CONFIG_FILE):
        default_config_path = CONFIG_FILE + ".default"
        if os.path.exists(default_config_path):
            shutil.copy(default_config_path, CONFIG_FILE)
    if not os.path.exists(DATA_FILE):
        default_data_path = DATA_FILE + ".default"
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
    "cover_title": "Pokémon Collection",
    "cover_subtitle": "My Binder Manager",
    "cover_owner": "Ash",
    "cover_color": "#e02424",
    "cover_featured_dex": 25,
    "cover_source": "dex"
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
    # Hardcoded Gen 1 list for offline fallback
    gen1_names = [
        "bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon", "charizard",
        "squirtle", "wartortle", "blastoise", "caterpie", "metapod", "butterfree",
        "weedle", "kakuna", "beedrill", "pidgey", "pidgeotto", "pidgeot",
        "rattata", "raticate", "spearow", "fearow", "ekans", "arbok",
        "pikachu", "raichu", "sandshrew", "sandslash", "nidoran-f", "nidorina",
        "nidoqueen", "nidoran-m", "nidorino", "nidoking", "clefairy", "clefable",
        "vulpix", "ninetales", "jigglypuff", "wigglytuff", "zubat", "golbat",
        "oddish", "gloom", "vileplume", "paras", "parasect", "venonat",
        "venomoth", "diglett", "dugtrio", "meowth", "persian", "psyduck",
        "golduck", "mankey", "primeape", "growlithe", "arcanine", "poliwag",
        "poliwhirl", "poliwrath", "abra", "kadabra", "alakazam", "machop",
        "machoke", "machamp", "bellsprout", "weepinbell", "victreebel", "tentacool",
        "tentacruel", "geodude", "graveler", "golem", "ponyta", "rapidash",
        "slowpoke", "slowbro", "magnemite", "magneton", "farfetchd", "doduo",
        "dodrio", "seel", "dewgong", "grimer", "muk", "shellder",
        "cloyster", "gastly", "haunter", "gengar", "onix", "drowzee",
        "hypno", "krabby", "kingler", "voltorb", "electrode", "exeggcute",
        "exeggutor", "cubone", "marowak", "hitmonlee", "hitmonchan", "lickitung",
        "koffing", "weezing", "rhyhorn", "rhydon", "chansey", "tangela",
        "kangaskhan", "horsea", "seadra", "goldeen", "seaking", "staryu",
        "starmie", "mr-mime", "scyther", "jynx", "electabuzz", "magmar",
        "pinsir", "tauros", "magikarp", "gyarados", "lapras", "ditto",
        "eevee", "vaporeon", "jolteon", "flareon", "porygon", "omanyte",
        "omastar", "kabuto", "kabutops", "aerodactyl", "snorlax", "articuno",
        "zapdos", "moltres", "dratini", "dragonair", "dragonite", "mewtwo",
        "mew"
    ]
    db = {}
    for index, name in enumerate(gen1_names):
        dex_id = index + 1
        db[name] = dex_id
        db[str(dex_id)] = name
    return db

def load_pokemon_database():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    
    print(f"{CLR_INFO}[i] Initializing Pokémon species database from PokeAPI...{CLR_RESET}")
    print("    (This fetches names & IDs to enable fast offline lookups.)")
    
    try:
        url = "https://pokeapi.co/api/v2/pokemon-species/?limit=1025"
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0'}
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode())
            results = data.get("results", [])
            
            db = {}
            for index, item in enumerate(results):
                dex_id = index + 1
                name = item["name"].lower()
                db[name] = dex_id
                db[str(dex_id)] = name
            
            with open(CACHE_FILE, "w", encoding="utf-8") as f:
                json.dump(db, f, indent=4)
                
            print(f"{CLR_SUCCESS}[+] Successfully cached {len(results)} Pokémon!{CLR_RESET}\n")
            return db
    except Exception as e:
        print(f"{CLR_WARNING}[!] Warning: Could not download PokeAPI database ({e}).{CLR_RESET}")
        print("    Falling back to built-in Generation 1 (first 151) offline list.")
        fallback = get_gen1_fallback()
        try:
            with open(CACHE_FILE, "w", encoding="utf-8") as f:
                json.dump(fallback, f, indent=4)
        except Exception:
            pass
        return fallback

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
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                config = json.load(f)
                # Ensure all keys are present
                for k, v in DEFAULT_CONFIG.items():
                    if k not in config:
                        config[k] = v
                return config
        except Exception:
            pass
    return DEFAULT_CONFIG.copy()

def save_config(config):
    try:
        with open(CONFIG_FILE, "w") as f:
            json.dump(config, f, indent=4)
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error saving configuration: {e}{CLR_RESET}")

def load_collection():
    collection = []
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r", newline="", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    # Parse numerical fields safely
                    row["Page"] = int(row["Page"])
                    row["Slot"] = int(row["Slot"])
                    row["Dex Number"] = int(row["Dex Number"]) if row["Dex Number"] else 0
                    collection.append(row)
        except Exception as e:
            print(f"{CLR_FAIL}[!] Error reading collection CSV: {e}{CLR_RESET}")
    return collection

def save_collection(collection):
    fieldnames = ["Page", "Slot", "Dex Number", "Name", "Condition", "Notes", "Date Added"]
    try:
        with open(DATA_FILE, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for card in collection:
                writer.writerow(card)
        return True
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error saving collection: {e}{CLR_RESET}")
        return False

def add_card_to_csv(card):
    fieldnames = ["Page", "Slot", "Dex Number", "Name", "Condition", "Notes", "Date Added"]
    file_exists = os.path.exists(DATA_FILE)
    try:
        with open(DATA_FILE, "a", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            if not file_exists:
                writer.writeheader()
            writer.writerow(card)
        return True
    except Exception as e:
        print(f"{CLR_FAIL}[!] Error writing to CSV file: {e}{CLR_RESET}")
        return False

# ==================== GOOGLE SHEETS SYNC MODULE ====================

def get_gspread_client():
    try:
        import gspread
        from google.oauth2.service_account import Credentials
    except ImportError:
        return None, "Google Sheets libraries (gspread, google-auth) are not installed.\n       Please run via 'run_binder.bat' to install dependencies."
        
    credentials_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "credentials.json"))
    if not os.path.exists(credentials_path):
        return None, "credentials.json key file is missing in the backend folder. Run Settings -> option 3 for help."
        
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
            worksheet = sh.add_worksheet(title="Binder Collection", rows="1000", cols="7")
            
        # Write headers
        headers = ["Page", "Slot", "Dex Number", "Name", "Condition", "Notes", "Date Added"]
        
        # Assemble rows
        rows_to_write = []
        for card in collection:
            rows_to_write.append([
                card["Page"],
                card["Slot"],
                card["Dex Number"] if card["Dex Number"] else "",
                card["Name"],
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
            if os.path.exists("credentials.json"):
                try:
                    with open("credentials.json") as f:
                        email = json.load(f).get("client_email", email)
                except Exception:
                    pass
            return False, f"Spreadsheet '{sheet_name}' was not found.\n       Ensure the sheet is created in your Google Drive and shared with:\n       {CLR_WARNING}{email}{CLR_RESET}"
        return False, f"Sync error: {e}"

def print_google_sheets_setup_guide():
    email = "your-service-account-email@...gserviceaccount.com"
    credentials_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "credentials.json"))
    if os.path.exists(credentials_path):
        try:
            with open(credentials_path) as f:
                email = json.load(f).get("client_email", email)
        except Exception:
            pass
            
    print(f"\n{CLR_HEADER}=== GOOGLE SHEETS SETUP INSTRUCTIONS ==={CLR_RESET}")
    print("To sync your binder database with Google Sheets:")
    print("1. Go to the Google Cloud Console (https://console.cloud.google.com/).")
    print("2. Create a new project and enable 'Google Sheets API' & 'Google Drive API'.")
    print("3. Go to 'Credentials' -> Click '+ CREATE CREDENTIALS' -> select 'Service Account'.")
    print("4. Complete the creation. Under the 'Keys' tab of that account, click 'Add Key' -> 'Create new key' -> JSON.")
    print("5. Save the downloaded JSON file in this directory and rename it exactly to:")
    print("   \033[92mcredentials.json\033[0m")
    print("6. Open Google Sheets in your browser, create a spreadsheet (e.g. named 'Pokemon Binder').")
    print("7. Share that Google Sheet (click 'Share' in Google Sheets) with your Service Account email:")
    print(f"   {CLR_WARNING}{email}{CLR_RESET}")
    print("   (Give it 'Editor' access.)")
    print("8. Make sure to Enable Sheets Integration and set the Spreadsheet Name in Settings!")

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
        
        # Sync with Google Sheets if enabled
        if config.get("gsheet_enabled"):
            print(f"{CLR_INFO}[i] Synchronizing changes to Google Sheets...{CLR_RESET}")
            success, msg = sync_with_google_sheets(config, load_collection())
            if success:
                print(f"{CLR_SUCCESS}[+] Google Sheets synced successfully!{CLR_RESET}")
            else:
                print(f"{CLR_WARNING}[!] Google Sheets sync failed: {msg}{CLR_RESET}")
                print("    (Card saved locally. You can sync again from settings later.)")
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

def configure_binder(config):
    while True:
        clear_screen()
        print(f"{CLR_HEADER}=== BINDER SETTINGS ==={CLR_RESET}")
        print(f"1. Binder Grid Size   : {config['rows']} rows x {config['cols']} columns ({config['rows']*config['cols']} pockets)")
        print(f"2. Sorting Mode       : {'National Dex Order' if config['mode'] == 'dex' else 'Sequential / Fill Order'}")
        
        gsheet_status = "Enabled" if config.get("gsheet_enabled") else "Disabled"
        sheet_name = config.get("gsheet_name") or "[Not Set]"
        print(f"3. Google Sheets Settings: {gsheet_status} (Sheet: '{sheet_name}')")
        print("4. Back to Main Menu")
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
        elif choice == '4' or choice == '':
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
        
        gsheet_status = "Syncing" if config.get("gsheet_enabled") else "Local Only"
        sheet_name = f" ('{config.get('gsheet_name')}')" if config.get("gsheet_enabled") and config.get("gsheet_name") else ""
        
        print(f"{CLR_HEADER}=================================================={CLR_RESET}")
        print(f"{CLR_HEADER}             POKÉMON BINDER MANAGER               {CLR_RESET}")
        print(f"{CLR_HEADER}=================================================={CLR_RESET}")
        print(f" Binder Size : {CLR_INFO}{config['rows']}x{config['cols']} ({config['rows']*config['cols']} pockets per page){CLR_RESET}")
        print(f" Sort Mode   : {CLR_INFO}{'National Dex' if config['mode'] == 'dex' else 'Sequential'}{CLR_RESET}")
        print(f" Sync Mode   : {CLR_INFO}{gsheet_status}{sheet_name}{CLR_RESET}")
        print(f" Total Cards : {CLR_SUCCESS}{len(collection)} saved in binder{CLR_RESET}")
        print("-" * 50)
        print(f" [{CLR_SUCCESS}1{CLR_RESET}] Add Card to Binder")
        print(f" [{CLR_SUCCESS}2{CLR_RESET}] View Binder Pages (Visual Grid)")
        print(f" [{CLR_SUCCESS}3{CLR_RESET}] Search & Remove Cards")
        print(f" [{CLR_SUCCESS}4{CLR_RESET}] Settings (Grid size, mode, Google Sheets)")
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
