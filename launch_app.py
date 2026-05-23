import os
import sys
import threading
import time
import webview

# Add backend directory to sys.path to allow importing server and binder scripts
backend_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "backend"))
sys.path.append(backend_path)

import pokemon_server as server

def start_server():
    server.main()

if __name__ == '__main__':
    # Start local HTTP server in a daemon thread
    t = threading.Thread(target=start_server)
    t.daemon = True
    t.start()
    
    # Wait 1.5 seconds for the server to load its cache database and bind to port 8000
    time.sleep(1.5)
    
    # Open the native WebView2 window (matching our zoomed layout size)
    webview.create_window(
        title='Pokémon Card Binder Manager',
        url='http://localhost:8000',
        width=1320,
        height=880,
        resizable=True,
        min_size=(1000, 680)
    )
    webview.start()
    
    # Explicitly exit the Python process when the window closes to free up port 8000
    os._exit(0)
