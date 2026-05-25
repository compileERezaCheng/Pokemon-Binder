import http.server
import socketserver
import json
import os
import sys
import urllib.parse
from datetime import datetime
import time

LAST_HEARTBEAT = time.time()

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

class BinderHTTPRequestHandler(http.server.BaseHTTPRequestHandler):
    def end_headers(self):
        # Call superclass end_headers directly. Caching headers are now managed on a per-response basis.
        super().end_headers()

    def do_GET(self):
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
            self.send_json(pokemon_binder.load_config(), cache_age=0)
            
        elif path == '/api/pokemon-db':
            # Pokémon Dex Species Database is 50KB and static, cache for 1 day
            db = pokemon_binder.load_pokemon_database()
            self.send_json(db, cache_age=86400)
            
        elif path == '/api/suggest-position':
            self.handle_suggest_position(query)
            
        else:
            self.send_error(404, "File Not Found")

    def do_POST(self):
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
        self.send_json({"success": True, "config": config})


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
            
            self.send_json({"success": True, "config": config})
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
            
            self.send_json({"success": True, "config": config})
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
                "config": pokemon_binder.load_config() # Reload to get updated user_id/token
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
            
        try:
            dex_id = int(data.get("dex_id", 0))
        except ValueError:
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
            server.server_close()
            os._exit(0)
            
        self.send_json({"success": True, "message": "Server is shutting down..."})
        threading.Thread(target=shutdown_process, args=(self.server,)).start()

    def handle_heartbeat(self):
        global LAST_HEARTBEAT
        LAST_HEARTBEAT = time.time()
        self.send_json({"success": True})

def monitor_heartbeat(server):
    # 15-second grace period at startup to allow Edge/Chrome app to launch and load JavaScript
    time.sleep(15)
    while True:
        time.sleep(2)
        if time.time() - LAST_HEARTBEAT > 12:
            # No heartbeat received for 12 seconds, assume app was closed
            print("[*] No active app connection detected. Automatically shutting down server...")
            server.shutdown()
            server.server_close()
            os._exit(0)

class ThreadedHTTPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    # This enables handling multiple requests in parallel without freezing the connection
    allow_reuse_address = True

def launch_browser():
    import subprocess
    import time
    import http.client
    import shutil

    # 1. Wait for server to be ready to avoid "Connection Refused" errors
    max_retries = 20
    ready = False
    for _ in range(max_retries):
        try:
            conn = http.client.HTTPConnection("localhost", PORT)
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

    # 2. Find Microsoft Edge for "App Mode" to make it look like a standalone app
    edge_cmd = "msedge.exe"
    edge_path = shutil.which(edge_cmd)
    
    if not edge_path:
        # Check standard Windows installation paths if not in system PATH
        possible_paths = [
            os.path.join(os.environ.get('ProgramFiles(x86)', 'C:\\Program Files (x86)'), 'Microsoft\\Edge\\Application\\msedge.exe'),
            os.path.join(os.environ.get('ProgramFiles', 'C:\\Program Files'), 'Microsoft\\Edge\\Application\\msedge.exe'),
            os.path.expanduser('~\\AppData\\Local\\Microsoft\\Edge\\Application\\msedge.exe')
        ]
        for path in possible_paths:
            if os.path.exists(path):
                edge_path = path
                break

    # 3. Launch in App Mode if Edge is found
    if edge_path:
        try:
            # --app flag removes address bar/tabs for the native app feel
            subprocess.Popen([edge_path, f'--app=http://localhost:{PORT}', '--window-size=1320,880'], 
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        except Exception:
            pass

    # Final Fallback: Open in the user's default browser if Edge App Mode fails
    try:
        import webbrowser
        webbrowser.open(f'http://localhost:{PORT}')
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
    try:
        # Make sure cache is loaded once on server startup to speed up response
        print("[*] Pre-loading PokeAPI local cache...")
        pokemon_binder.load_pokemon_database()
        
        # Download default icon if missing
        try:
            pokemon_binder.download_default_icon()
        except Exception:
            pass

        # Launch browser in a background thread
        import threading
        browser_thread = threading.Thread(target=launch_browser)
        browser_thread.daemon = True
        browser_thread.start()
        
        server_address = ('', PORT)
        httpd = ThreadedHTTPServer(server_address, BinderHTTPRequestHandler)
        
        # Heartbeat monitor
        monitor_thread = threading.Thread(target=monitor_heartbeat, args=(httpd,))
        monitor_thread.daemon = True
        monitor_thread.start()
        
        print(f"\n=======================================================")
        print(f"   POKÉMON BINDER MANAGER WEB API SERVER")
        print(f"   Running on http://localhost:{PORT}")
        print(f"   Press Ctrl+C in this window to stop the server")
        print(f"=======================================================\n")
        
        httpd.serve_forever()
    except Exception as e:
        import traceback
        with open("startup_error.log", "w") as f:
            f.write(str(e) + "\n")
            f.write(traceback.format_exc())
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nShutting down server...")
        sys.exit(0)

if __name__ == '__main__':
    main()
