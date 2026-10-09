import http.server
import socketserver
import json
import errno
import os
import sys
import urllib.parse
from datetime import datetime

# Add the current directory to sys.path so we can import local modules
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import pokemon_binder

# Logic to handle internal assets vs external data persistence
if getattr(sys, 'frozen', False):
    # Running as a bundled EXE
    INTERNAL_DIR = sys._MEIPASS
    FRONTEND_DIR = os.path.join(INTERNAL_DIR, "frontend")
    INTERNAL_DATA_DIR = os.path.join(INTERNAL_DIR, "data")
    
    # Path for logs in APPDATA
    LOG_DIR = os.path.join(os.environ.get('APPDATA'), "PokemonBinder")
    if not os.path.exists(LOG_DIR):
        os.makedirs(LOG_DIR, exist_ok=True)
    STARTUP_LOG = os.path.join(LOG_DIR, "startup_error.log")
    
    # Redirect stdout and stderr to a log file in APPDATA to avoid crashes in --noconsole mode
    # when the app tries to print but there is no terminal.
    sys.stdout = open(os.path.join(LOG_DIR, "server_stdout.log"), "w", encoding="utf-8", buffering=1)
    sys.stderr = open(os.path.join(LOG_DIR, "server_stderr.log"), "w", encoding="utf-8", buffering=1)
else:
    # Running in development
    FRONTEND_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "frontend"))
    INTERNAL_DATA_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "data"))
    STARTUP_LOG = "startup_error.log"

DATA_DIR = pokemon_binder.DATA_DIR
PORT = 8080
_SINGLE_INSTANCE_HANDLE = None
INSTANCE_TOKEN = os.environ.get('POKEMON_BINDER_INSTANCE', '')
PUBLIC_SETTINGS = {
    'rows', 'cols', 'mode', 'gsheet_enabled', 'gsheet_name', 'firebase_enabled',
    'firebase_email', 'username', 'cover_title', 'cover_subtitle', 'cover_owner',
    'cover_color', 'cover_featured_dex', 'cover_source', 'cover_image_url',
    'cover_image_path', 'profile_picture_source', 'profile_featured_dex',
    'profile_image_url', 'profile_image_path',
}

def public_settings(config):
    return {key: config[key] for key in PUBLIC_SETTINGS if key in config}

class BinderHTTPRequestHandler(http.server.BaseHTTPRequestHandler):
    def allowed_request(self, write=False):
        host = self.headers.get('Host', '')
        origin = self.headers.get('Origin')
        if host != f'127.0.0.1:{PORT}' or self.client_address[0] != '127.0.0.1':
            self.send_error(403, 'Local access only')
            return False
        if origin and origin != f'http://127.0.0.1:{PORT}':
            self.send_error(403, 'Unexpected origin')
            return False
        if write and self.headers.get('Sec-Fetch-Site', 'same-origin') not in ('same-origin', 'none'):
            self.send_error(403, 'Unexpected origin')
            return False
        bodyless = {'/api/heartbeat', '/api/shutdown', '/api/sync', '/api/firebase/logout'}
        if write and self.path not in bodyless and self.headers.get('Content-Type', '').split(';')[0].strip() != 'application/json':
            self.send_error(415, 'JSON required')
            return False
        return True

    def end_headers(self):
        # Call superclass end_headers directly. Caching headers are now managed on a per-response basis.
        super().end_headers()

    def do_GET(self):
        if not self.allowed_request():
            return
        parsed_url = urllib.parse.urlparse(self.path)
        path = parsed_url.path
        query = urllib.parse.parse_qs(parsed_url.query)

        # Static files mapping
        if path == '/' or path == '/loading.html':
            self.serve_file(os.path.join(FRONTEND_DIR, 'loading.html'), 'text/html; charset=utf-8', cache_age=0)
        elif path == '/index.html':
            self.serve_file(os.path.join(FRONTEND_DIR, 'index.html'), 'text/html; charset=utf-8', cache_age=0)
        elif path == '/index.css':
            self.serve_file(os.path.join(FRONTEND_DIR, 'index.css'), 'text/css; charset=utf-8', cache_age=0)
        elif path == '/index.js':
            self.serve_file(os.path.join(FRONTEND_DIR, 'index.js'), 'application/javascript; charset=utf-8', cache_age=0)
        elif path == '/cover_image.png':
            filepath = os.path.join(DATA_DIR, "cover_image.png")
            if os.path.exists(filepath):
                self.serve_file(filepath, 'image/png', cache_age=0)
            else:
                self.serve_file(os.path.join(INTERNAL_DATA_DIR, 'cover_image.png'), 'image/png')
        elif path == '/profile_image.png':
            filepath = os.path.join(DATA_DIR, "profile_image.png")
            if os.path.exists(filepath):
                self.serve_file(filepath, 'image/png', cache_age=0)
            else:
                # If no custom profile photo, serve the cover image as fallback
                self.serve_file(os.path.join(INTERNAL_DATA_DIR, 'cover_image.png'), 'image/png')
        elif path == '/favicon.ico':
            # Try to serve from external data first, fallback to internal
            ico_path = os.path.join(DATA_DIR, 'pokeball.ico')
            if not os.path.exists(ico_path):
                ico_path = os.path.join(INTERNAL_DATA_DIR, 'pokeball.ico')
            self.serve_file(ico_path, 'image/x-icon', cache_age=86400)
            
        # API Endpoints
        elif path == '/api/collection':
            self.send_json(pokemon_binder.load_collection(), cache_age=0)
            
        elif path == '/api/settings':
            self.send_json(public_settings(pokemon_binder.load_config()), cache_age=0)
        elif path == '/api/instance':
            self.send_json({'instance': INSTANCE_TOKEN}, cache_age=0)
            
        elif path == '/api/pokemon-db':
            # Pokémon Dex Species Database is 50KB and static, cache for 1 day
            db = pokemon_binder.load_pokemon_database()
            self.send_json(db, cache_age=86400)
            
        elif path == '/api/suggest-position':
            self.handle_suggest_position(query)
            
        else:
            self.send_error(404, "File Not Found")

    def do_POST(self):
        if not self.allowed_request(write=True):
            return
        parsed_url = urllib.parse.urlparse(self.path)
        path = parsed_url.path
        
        # Read body content
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        try:
            data = json.loads(post_data.decode('utf-8')) if post_data else {}
        except Exception as e:
            self.send_json({"success": False, "error": f"Invalid JSON body: {str(e)}"}, status=400)
            return

        if path == '/api/settings':
            self.handle_save_settings(data)
        elif path == '/api/add':
            self.handle_add_card(data)
        elif path == '/api/remove':
            self.handle_remove_card(data)
        elif path == '/api/sync':
            self.handle_sync_gspread()
        elif path == '/api/upload-cover-image':
            self.handle_upload_cover_image(data)
        elif path == '/api/upload-profile-image':
            self.handle_upload_profile_image(data)
        elif path == '/api/firebase/login':
            self.handle_firebase_login(data)
        elif path == '/api/firebase/logout':
            self.handle_firebase_logout()
        elif path == '/api/shutdown':
            self.handle_shutdown()
        elif path == '/api/heartbeat':
            self.handle_heartbeat()
        else:
            self.send_error(404, "API Endpoint Not Found")

    def serve_file(self, filename, content_type, cache_age=0):
        if not os.path.exists(filename):
            self.send_error(404, f"File {filename} not found")
            return
        
        try:
            with open(filename, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(content)))
            if cache_age > 0:
                self.send_header('Cache-Control', f'public, max-age={cache_age}')
            else:
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_error(500, f"Error reading file: {str(e)}")

    def send_json(self, data, status=200, cache_age=0):
        try:
            response_content = json.dumps(data).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(response_content)))
            if cache_age > 0:
                self.send_header('Cache-Control', f'public, max-age={cache_age}')
            else:
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
            self.end_headers()
            self.wfile.write(response_content)
        except Exception as e:
            # Fallback if serialization fails
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f"{{\"success\": false, \"error\": \"Serialization failed: {str(e)}\"}}".encode('utf-8'))


    def handle_suggest_position(self, query_params):
        try:
            dex_id = int(query_params.get('id', [0])[0])
        except ValueError:
            dex_id = 0
            
        config = pokemon_binder.load_config()
        collection = pokemon_binder.load_collection()
        rows = config.get("rows", 3)
        cols = config.get("cols", 3)
        mode = config.get("mode", "dex")
        
        # Calculate suggested position
        if mode == "dex" and dex_id > 0:
            page, slot = pokemon_binder.get_slot_coordinates(dex_id, rows, cols)
            position_type = "National Dex position"
        else:
            next_idx = pokemon_binder.find_next_sequential_slot(collection, rows, cols)
            page, slot = pokemon_binder.get_slot_coordinates(next_idx, rows, cols)
            position_type = "First available binder slot"
            
        # Check if slot is occupied
        occupied_cards = [
            {"name": c["Name"], "dex": c["Dex Number"], "condition": c["Condition"], "notes": c["Notes"]}
            for c in collection if c["Page"] == page and c["Slot"] == slot
        ]
        
        self.send_json({
            "page": page,
            "slot": slot,
            "position_type": position_type,
            "occupied": len(occupied_cards) > 0,
            "occupied_cards": occupied_cards
        })

    def handle_save_settings(self, data):
        config = pokemon_binder.load_config()
        
        # Merge incoming settings
        for key in ["rows", "cols", "mode", "gsheet_enabled", "gsheet_name", 
                    "cover_title", "cover_subtitle", "cover_owner", "cover_color", "cover_featured_dex",
                    "cover_source", "cover_image_url", "username", "profile_picture_source", 
                    "profile_featured_dex", "profile_image_url", "profile_image_path"]:
            if key in data:
                if key == "rows" or key == "cols":
                    try:
                        val = int(data[key])
                        if val >= 1:
                            config[key] = val
                    except ValueError:
                        pass
                elif key == "cover_featured_dex" or key == "profile_featured_dex":
                    try:
                        config[key] = int(data[key])
                    except ValueError:
                        config[key] = 0
                elif key == "gsheet_enabled":
                    config[key] = bool(data[key])
                elif key == "mode":
                    if data[key] in ["dex", "sequential"]:
                        config[key] = data[key]
                else:
                    config[key] = str(data[key]).strip()
                    
        pokemon_binder.save_config(config)
        if config.get("firebase_enabled"):
            try:
                pokemon_binder.push_profile_to_firebase(config)
            except Exception:
                pass
        self.send_json({"success": True, "config": public_settings(config)})


    def handle_upload_cover_image(self, data):
        image_data = data.get("image_data")
        if not image_data:
            self.send_json({"success": False, "error": "No image data received"}, status=400)
            return
            
        try:
            if "," in image_data:
                header, base64_str = image_data.split(",", 1)
            else:
                base64_str = image_data
                
            import base64
            decoded_bytes = base64.b64decode(base64_str)
            
            filepath = os.path.join(DATA_DIR, "cover_image.png")
            with open(filepath, "wb") as f:
                f.write(decoded_bytes)
                
            config = pokemon_binder.load_config()
            config["cover_source"] = "upload"
            config["cover_image_path"] = "cover_image.png"
            pokemon_binder.save_config(config)
            
            self.send_json({"success": True, "config": public_settings(config)})
        except Exception as e:
            self.send_json({"success": False, "error": f"Failed to save image: {str(e)}"}, status=500)

    def handle_upload_profile_image(self, data):
        image_data = data.get("image_data")
        if not image_data:
            self.send_json({"success": False, "error": "No image data received"}, status=400)
            return
            
        try:
            if "," in image_data:
                header, base64_str = image_data.split(",", 1)
            else:
                base64_str = image_data
                
            import base64
            decoded_bytes = base64.b64decode(base64_str)
            
            filepath = os.path.join(DATA_DIR, "profile_image.png")
            with open(filepath, "wb") as f:
                f.write(decoded_bytes)
                
            config = pokemon_binder.load_config()
            config["profile_picture_source"] = "upload"
            config["profile_image_path"] = "profile_image.png"
            pokemon_binder.save_config(config)
            if config.get("firebase_enabled"):
                try:
                    pokemon_binder.push_profile_to_firebase(config)
                except Exception:
                    pass
            
            self.send_json({"success": True, "config": public_settings(config)})
        except Exception as e:
            self.send_json({"success": False, "error": f"Failed to save image: {str(e)}"}, status=500)

    def handle_firebase_login(self, data):
        email = data.get("email")
        password = data.get("password")
        if not email or not password:
            self.send_json({"success": False, "error": "Email and password are required"}, status=400)
            return
            
        config = pokemon_binder.load_config()
        config["firebase_enabled"] = True
        config["firebase_email"] = email
        config["firebase_password"] = password
        
        success, msg = pokemon_binder.firebase_sign_in(config)
        if success:
            # Trigger immediate initial sync
            collection = pokemon_binder.load_collection()
            sync_success, sync_msg = pokemon_binder.sync_with_firebase(config, collection)
            self.send_json({
                "success": True, 
                "message": f"Login successful! {sync_msg}",
                "config": public_settings(pokemon_binder.load_config())
            })
        else:
            self.send_json({"success": False, "error": msg}, status=401)

    def handle_firebase_logout(self):
        config = pokemon_binder.load_config()
        success, msg = pokemon_binder.firebase_logout(config)
        self.send_json({"success": success, "message": msg})

    def handle_add_card(self, data):
        # Validate data
        name = data.get("name", "").strip().lower()
        if not name:
            self.send_json({"success": False, "error": "Pokémon name cannot be empty"}, status=400)
            return
            
        dex_value = data.get("dex_id", 0)
        if isinstance(dex_value, dict):
            dex_value = dex_value.get("id", 0)
        try:
            dex_id = int(dex_value)
        except (TypeError, ValueError):
            dex_id = 0
            
        try:
            page = int(data.get("page", 1))
            slot = int(data.get("slot", 1))
            if page < 1 or slot < 1:
                raise ValueError
        except ValueError:
            self.send_json({"success": False, "error": "Invalid Page or Slot coordinates"}, status=400)
            return
            
        condition = data.get("condition", "NM").strip().upper()
        if condition not in ["NM", "LP", "MP", "HP"]:
            condition = "NM"
            
        notes = data.get("notes", "").strip()
        card_type = data.get("type", "Normal").strip()
        
        # Create card dictionary
        new_card = {
            "Page": page,
            "Slot": slot,
            "Dex Number": dex_id if dex_id > 0 else "",
            "Name": name,
            "Type": card_type,
            "Condition": condition,
            "Notes": notes,
            "Date Added": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        }
        
        # Save to local CSV file
        if pokemon_binder.add_card_to_csv(new_card):
            gsheet_success = True
            gsheet_msg = ""
            firebase_success = True
            firebase_msg = ""
            config = pokemon_binder.load_config()
            
            if config.get("gsheet_enabled") or config.get("firebase_enabled"):
                collection = pokemon_binder.load_collection()
                
                if config.get("gsheet_enabled"):
                    gsheet_success, gsheet_msg = pokemon_binder.sync_with_google_sheets(config, collection)
                    
                if config.get("firebase_enabled"):
                    firebase_success, firebase_msg = pokemon_binder.sync_with_firebase(config, collection)
            
            self.send_json({
                "success": True,
                "card": new_card,
                "gsheet_synced": config.get("gsheet_enabled"),
                "gsheet_success": gsheet_success,
                "gsheet_message": gsheet_msg,
                "firebase_synced": config.get("firebase_enabled"),
                "firebase_success": firebase_success,
                "firebase_message": firebase_msg
            })
        else:
            self.send_json({"success": False, "error": "Could not write card to local CSV database"}, status=500)

    def handle_remove_card(self, data):
        # We need to know which card to remove.
        # Removing by page, slot, name, and optionally date_added makes it specific.
        try:
            page = int(data.get("page"))
            slot = int(data.get("slot"))
            name = data.get("name", "").strip().lower()
            date_added = data.get("date_added", "").strip()
        except (ValueError, TypeError):
            self.send_json({"success": False, "error": "Missing or invalid Page/Slot specifications"}, status=400)
            return
            
        collection = pokemon_binder.load_collection()
        found_idx = -1
        
        # Look for matching card
        for idx, card in enumerate(collection):
            if card["Page"] == page and card["Slot"] == slot and card["Name"].lower() == name:
                if date_added and card.get("Date Added", "").strip() != date_added:
                    continue
                found_idx = idx
                break
                
        if found_idx == -1:
            # Fallback to page and slot only if name matches partially or is empty
            for idx, card in enumerate(collection):
                if card["Page"] == page and card["Slot"] == slot:
                    found_idx = idx
                    break
                    
        if found_idx == -1:
            self.send_json({"success": False, "error": "Card not found in collection"}, status=404)
            return
            
        removed_card = collection.pop(found_idx)
        
        if pokemon_binder.save_collection(collection):
            gsheet_success = True
            gsheet_msg = ""
            firebase_success = True
            firebase_msg = ""
            config = pokemon_binder.load_config()
            
            if config.get("gsheet_enabled"):
                gsheet_success, gsheet_msg = pokemon_binder.sync_with_google_sheets(config, collection)
                
            if config.get("firebase_enabled"):
                firebase_success, firebase_msg = pokemon_binder.sync_with_firebase(config, collection)
                
            self.send_json({
                "success": True,
                "removed_card": removed_card,
                "gsheet_synced": config.get("gsheet_enabled"),
                "gsheet_success": gsheet_success,
                "gsheet_message": gsheet_msg,
                "firebase_synced": config.get("firebase_enabled"),
                "firebase_success": firebase_success,
                "firebase_message": firebase_msg
            })
        else:
            self.send_json({"success": False, "error": "Failed to update CSV database"}, status=500)

    def handle_sync_gspread(self):
        config = pokemon_binder.load_config()
        gsheet_active = config.get("gsheet_enabled", False)
        firebase_active = config.get("firebase_enabled", False)
        
        if not gsheet_active and not firebase_active:
            self.send_json({"success": False, "error": "No sync integrations (Google Sheets or Firebase) are enabled."}, status=400)
            return
            
        collection = pokemon_binder.load_collection()
        results = []
        errors = []
        
        if gsheet_active:
            success, msg = pokemon_binder.sync_with_google_sheets(config, collection)
            if success:
                results.append("Google Sheets synced")
            else:
                errors.append(f"Google Sheets: {msg}")
                
        if firebase_active:
            success, msg = pokemon_binder.sync_with_firebase(config, collection)
            if success:
                results.append("Firebase synced")
            else:
                errors.append(f"Firebase: {msg}")
                
        if errors:
            self.send_json({"success": False, "error": " & ".join(errors)}, status=500)
        else:
            self.send_json({"success": True, "message": " & ".join(results) + " successfully!"})

    def handle_shutdown(self):
        import threading
        import time
        
        def shutdown_process(server):
            time.sleep(0.5)  # Wait 500ms to allow response to send fully
            server.shutdown()
            
        self.send_json({"success": True, "message": "Server is shutting down..."})
        threading.Thread(target=shutdown_process, args=(self.server,), daemon=True).start()

    def handle_heartbeat(self):
        self.send_json({"success": True})

class ThreadedHTTPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    # This enables handling multiple requests in parallel without freezing the connection
    daemon_threads = True


def acquire_single_instance():
    global _SINGLE_INSTANCE_HANDLE
    if os.name != "nt":
        return True

    import ctypes
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel32.CloseHandle.restype = ctypes.c_int

    ctypes.set_last_error(0)
    handle = kernel32.CreateMutexW(None, False, "Local\\PokebinderSingleInstance")
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
        kernel32.CloseHandle(handle)
        return False

    _SINGLE_INSTANCE_HANDLE = handle
    return True


def show_startup_message(message, title="PokéBinder"):
    if os.name == "nt":
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, message, title, 0x10)
    else:
        print(message)


class SystemTrayIcon:
    WM_TRAY = 0x8001
    WM_CLOSE = 0x0010
    WM_DESTROY = 0x0002
    WM_LBUTTONDBLCLK = 0x0203
    WM_RBUTTONUP = 0x0205
    WM_CONTEXTMENU = 0x007B
    WM_NULL = 0x0000
    NIM_ADD = 0x00000000
    NIM_DELETE = 0x00000002
    NIF_MESSAGE = 0x00000001
    NIF_ICON = 0x00000002
    NIF_TIP = 0x00000004
    IMAGE_ICON = 1
    LR_LOADFROMFILE = 0x0010
    WS_EX_TOOLWINDOW = 0x00000080
    WS_POPUP = 0x80000000
    MF_STRING = 0x00000000
    MF_SEPARATOR = 0x00000800
    TPM_RETURNCMD = 0x0100
    TPM_RIGHTBUTTON = 0x0002
    OPEN_COMMAND = 1001
    EXIT_COMMAND = 1002

    def __init__(self, on_open, on_exit):
        import threading
        self.on_open = on_open
        self.on_exit = on_exit
        self._ready = threading.Event()
        self._thread = None
        self._hwnd = None
        self._kernel = None
        self._user = None
        self._shell = None
        self._notify_data = None
        self._icon = None
        self._added = False
        self._taskbar_created = 0
        self._error = None

    def start(self):
        import threading
        if os.name != "nt":
            raise OSError("The system tray is available on Windows only.")
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        if not self._ready.wait(5):
            self.stop()
            raise RuntimeError("Timed out creating the PokéBinder tray icon.")
        if self._error:
            raise RuntimeError(f"Could not create the PokéBinder tray icon: {self._error}") from self._error

    def stop(self):
        if self._hwnd and self._user:
            self._user.PostMessageW(self._hwnd, self.WM_CLOSE, 0, 0)
        if self._thread and self._thread is not __import__("threading").current_thread():
            self._thread.join(timeout=2)

    def _run(self):
        import ctypes
        from ctypes import wintypes

        LRESULT = ctypes.c_ssize_t
        WPARAM = ctypes.c_size_t
        LPARAM = ctypes.c_ssize_t
        WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, WPARAM, LPARAM)

        class WNDCLASSW(ctypes.Structure):
            _fields_ = [
                ("style", wintypes.UINT), ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int),
                ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HANDLE),
                ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR),
            ]

        class POINT(ctypes.Structure):
            _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

        class MSG(ctypes.Structure):
            _fields_ = [
                ("hwnd", wintypes.HWND), ("message", wintypes.UINT),
                ("wParam", WPARAM), ("lParam", LPARAM), ("time", wintypes.DWORD),
                ("pt", POINT), ("lPrivate", wintypes.DWORD),
            ]

        class GUID(ctypes.Structure):
            _fields_ = [
                ("Data1", wintypes.DWORD), ("Data2", wintypes.WORD),
                ("Data3", wintypes.WORD), ("Data4", wintypes.BYTE * 8),
            ]

        class NOTIFYICONDATAW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD), ("hWnd", wintypes.HWND),
                ("uID", wintypes.UINT), ("uFlags", wintypes.UINT),
                ("uCallbackMessage", wintypes.UINT), ("hIcon", wintypes.HICON),
                ("szTip", wintypes.WCHAR * 128), ("dwState", wintypes.DWORD),
                ("dwStateMask", wintypes.DWORD), ("szInfo", wintypes.WCHAR * 256),
                ("uTimeoutOrVersion", wintypes.UINT), ("szInfoTitle", wintypes.WCHAR * 64),
                ("dwInfoFlags", wintypes.DWORD), ("guidItem", GUID),
                ("hBalloonIcon", wintypes.HICON),
            ]

        try:
            self._user = ctypes.windll.user32
            self._kernel = ctypes.windll.kernel32
            self._shell = ctypes.windll.shell32
            self._point_type = POINT
            self._configure_apis(ctypes, wintypes, WNDCLASSW, MSG, NOTIFYICONDATAW, POINT, WPARAM, LPARAM)
            hinstance = self._kernel.GetModuleHandleW(None)
            class_name = "PokemonBinderTrayWindow"
            self._taskbar_created = self._user.RegisterWindowMessageW("TaskbarCreated")
            self._wnd_proc = WNDPROC(self._window_proc)
            window_class = WNDCLASSW()
            window_class.lpfnWndProc = self._wnd_proc
            window_class.hInstance = hinstance
            window_class.lpszClassName = class_name
            if not self._user.RegisterClassW(ctypes.byref(window_class)) and ctypes.get_last_error() != 1410:
                raise ctypes.WinError(ctypes.get_last_error())

            self._hwnd = self._user.CreateWindowExW(
                self.WS_EX_TOOLWINDOW, class_name, "PokéBinder", self.WS_POPUP,
                0, 0, 0, 0, None, None, hinstance, None
            )
            if not self._hwnd:
                raise ctypes.WinError(ctypes.get_last_error())

            self._notify_data = NOTIFYICONDATAW()
            self._notify_data.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
            self._notify_data.hWnd = self._hwnd
            self._notify_data.uID = 1
            self._notify_data.uFlags = self.NIF_MESSAGE | self.NIF_ICON | self.NIF_TIP
            self._notify_data.uCallbackMessage = self.WM_TRAY
            self._notify_data.szTip = "PokéBinder"
            icon_path = os.path.join(INTERNAL_DATA_DIR, "pokeball.ico")
            self._icon = self._user.LoadImageW(None, icon_path, self.IMAGE_ICON, 16, 16, self.LR_LOADFROMFILE)
            if not self._icon:
                raise ctypes.WinError(ctypes.get_last_error())
            self._notify_data.hIcon = self._icon
            self._add_icon()
            self._ready.set()

            message = MSG()
            while self._user.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
                self._user.TranslateMessage(ctypes.byref(message))
                self._user.DispatchMessageW(ctypes.byref(message))
        except Exception as error:
            self._error = error
            self._ready.set()
        finally:
            self._remove_icon()
            if self._hwnd and self._user:
                self._user.DestroyWindow(self._hwnd)
                self._hwnd = None
            if self._icon and self._user:
                self._user.DestroyIcon(self._icon)
                self._icon = None

    def _configure_apis(self, ctypes, wintypes, wndclass, message, notify_data, point, wparam, lparam):
        self._kernel.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
        self._kernel.GetModuleHandleW.restype = wintypes.HINSTANCE
        self._user.RegisterWindowMessageW.argtypes = [wintypes.LPCWSTR]
        self._user.RegisterWindowMessageW.restype = wintypes.UINT
        self._user.RegisterClassW.argtypes = [ctypes.POINTER(wndclass)]
        self._user.RegisterClassW.restype = wintypes.ATOM
        self._user.CreateWindowExW.argtypes = [
            wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
            ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID,
        ]
        self._user.CreateWindowExW.restype = wintypes.HWND
        self._user.LoadImageW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR, wintypes.UINT, ctypes.c_int, ctypes.c_int, wintypes.UINT]
        self._user.LoadImageW.restype = wintypes.HICON
        self._user.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wparam, lparam]
        self._user.DefWindowProcW.restype = ctypes.c_ssize_t
        self._user.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wparam, lparam]
        self._user.PostMessageW.restype = wintypes.BOOL
        self._user.GetMessageW.argtypes = [ctypes.POINTER(message), wintypes.HWND, wintypes.UINT, wintypes.UINT]
        self._user.GetMessageW.restype = ctypes.c_int
        self._user.TranslateMessage.argtypes = [ctypes.POINTER(message)]
        self._user.DispatchMessageW.argtypes = [ctypes.POINTER(message)]
        self._user.DestroyWindow.argtypes = [wintypes.HWND]
        self._user.DestroyIcon.argtypes = [wintypes.HICON]
        self._user.PostQuitMessage.argtypes = [ctypes.c_int]
        self._user.CreatePopupMenu.restype = wintypes.HMENU
        self._user.AppendMenuW.argtypes = [wintypes.HMENU, wintypes.UINT, ctypes.c_size_t, wintypes.LPCWSTR]
        self._user.DestroyMenu.argtypes = [wintypes.HMENU]
        self._user.SetForegroundWindow.argtypes = [wintypes.HWND]
        self._user.GetCursorPos.argtypes = [ctypes.POINTER(point)]
        self._user.TrackPopupMenu.argtypes = [
            wintypes.HMENU, wintypes.UINT, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            wintypes.HWND, wintypes.LPVOID,
        ]
        self._user.TrackPopupMenu.restype = wintypes.UINT
        self._shell.Shell_NotifyIconW.argtypes = [wintypes.DWORD, ctypes.POINTER(notify_data)]
        self._shell.Shell_NotifyIconW.restype = wintypes.BOOL

    def _add_icon(self):
        import ctypes
        if self._shell.Shell_NotifyIconW(self.NIM_ADD, ctypes.byref(self._notify_data)):
            self._added = True
        else:
            import ctypes
            raise ctypes.WinError(ctypes.get_last_error())

    def _remove_icon(self):
        if self._added:
            import ctypes
            self._shell.Shell_NotifyIconW(self.NIM_DELETE, ctypes.byref(self._notify_data))
            self._added = False

    def _window_proc(self, hwnd, message, wparam, lparam):
        if message == self._taskbar_created:
            self._add_icon()
            return 0
        if message == self.WM_TRAY:
            if lparam == self.WM_LBUTTONDBLCLK:
                self.on_open()
            elif lparam in (self.WM_RBUTTONUP, self.WM_CONTEXTMENU):
                self._show_menu(hwnd)
            return 0
        if message == self.WM_CLOSE:
            self._remove_icon()
            self._user.DestroyWindow(hwnd)
            return 0
        if message == self.WM_DESTROY:
            self._hwnd = None
            self._user.PostQuitMessage(0)
            return 0
        return self._user.DefWindowProcW(hwnd, message, wparam, lparam)

    def _show_menu(self, hwnd):
        import ctypes
        from ctypes import wintypes

        menu = self._user.CreatePopupMenu()
        if not menu:
            return
        self._user.AppendMenuW(menu, self.MF_STRING, self.OPEN_COMMAND, "Abrir PokéBinder")
        self._user.AppendMenuW(menu, self.MF_SEPARATOR, 0, None)
        self._user.AppendMenuW(menu, self.MF_STRING, self.EXIT_COMMAND, "Sair")
        self._user.SetForegroundWindow(hwnd)
        point = self._point_type()
        self._user.GetCursorPos(ctypes.byref(point))
        command = self._user.TrackPopupMenu(
            menu, self.TPM_RETURNCMD | self.TPM_RIGHTBUTTON,
            point.x, point.y, 0, hwnd, None
        )
        self._user.DestroyMenu(menu)
        self._user.PostMessageW(hwnd, self.WM_NULL, 0, 0)
        if command == self.OPEN_COMMAND:
            self.on_open()
        elif command == self.EXIT_COMMAND:
            self.on_exit()


def launch_browser():
    import time
    import http.client
    import webbrowser

    # 1. Wait for server to be ready to avoid "Connection Refused" errors
    max_retries = 20
    ready = False
    for _ in range(max_retries):
        try:
            conn = http.client.HTTPConnection("127.0.0.1", PORT)
            conn.request("GET", "/")
            res = conn.getresponse()
            if res.status == 200:
                ready = True
                break
        except Exception:
            pass
        time.sleep(0.5)

    if not ready:
        return

    try:
        webbrowser.open(f'http://127.0.0.1:{PORT}')
    except Exception:
        pass

def check_setup_shortcut():
    if not getattr(sys, 'frozen', False):
        return
        
    import ctypes
    import subprocess
    from ctypes import wintypes
    
    # Robustly find the Desktop path (works for OneDrive and localized names like "Ambiente de Trabalho")
    CSIDL_DESKTOP = 0x0000
    SHGFP_TYPE_CURRENT = 0
    buf = ctypes.create_unicode_buffer(wintypes.MAX_PATH)
    ctypes.windll.shell32.SHGetFolderPathW(0, CSIDL_DESKTOP, 0, SHGFP_TYPE_CURRENT, buf)
    desktop = buf.value
    
    shortcut_path = os.path.join(desktop, 'Pokemon Binder.lnk')
    
    if not os.path.exists(shortcut_path):
        MB_YESNO = 0x04
        MB_ICONQUESTION = 0x20
        IDYES = 6
        
        res = ctypes.windll.user32.MessageBoxW(0, 
            "Do you want to create a Desktop shortcut for Pokémon Binder Manager?", 
            "Pokémon Binder Setup", 
            MB_YESNO | MB_ICONQUESTION)
            
        if res == IDYES:
            try:
                target = sys.executable
                work_dir = os.path.dirname(sys.executable)
                # Use the icon embedded in the EXE itself
                ico = target
                
                # Using powershell to create shortcut safely
                ps_cmd = f'$s=(New-Object -COM WScript.Shell).CreateShortcut("{shortcut_path}");$s.TargetPath="{target}";$s.WorkingDirectory="{work_dir}";$s.IconLocation="{ico}";$s.Save()'
                subprocess.run(['powershell', '-Command', ps_cmd], capture_output=True)
            except Exception:
                pass

def main():
    import threading
    httpd = None
    tray = None
    try:
        if not acquire_single_instance():
            show_startup_message("PokéBinder is already running. Use the existing tray icon.")
            return

        try:
            httpd = ThreadedHTTPServer(("127.0.0.1", PORT), BinderHTTPRequestHandler)
        except OSError as error:
            if error.errno == errno.EADDRINUSE or getattr(error, "winerror", None) == 10048:
                show_startup_message(
                    f"Port {PORT} is already in use. PokéBinder may already be running; check the tray icon near the clock."
                )
                return
            raise

        # Make sure cache is loaded once on server startup to speed up response
        print("[*] Pre-loading PokeAPI local cache...")
        pokemon_binder.load_pokemon_database()
        
        # Download default icon if missing
        try:
            pokemon_binder.download_default_icon()
        except Exception:
            pass

        # The packaged WebView owns the window; development mode keeps the tray/browser workflow.
        if not INSTANCE_TOKEN:
            def open_browser():
                threading.Thread(target=launch_browser, daemon=True).start()

            def exit_server():
                threading.Thread(target=httpd.shutdown, daemon=True).start()

            tray = SystemTrayIcon(open_browser, exit_server)
            tray.start()
            open_browser()
        
        print(f"\n=======================================================")
        print(f"   POKÉMON BINDER MANAGER WEB API SERVER")
        print(f"   Running on http://127.0.0.1:{PORT}")
        if not INSTANCE_TOKEN:
            print(f"   Use the PokéBinder tray icon to reopen the binder or exit")
        print(f"=======================================================\n")
        
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...")
    except Exception as e:
        import traceback
        try:
            with open(STARTUP_LOG, "w", encoding="utf-8") as f:
                f.write(str(e) + "\n")
                f.write(traceback.format_exc())
        except Exception:
            pass
        show_startup_message(f"PokéBinder could not start: {e}")
    finally:
        if tray:
            tray.stop()
        if httpd:
            httpd.server_close()

if __name__ == '__main__':
    main()
