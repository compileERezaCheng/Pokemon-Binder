# 📖 Pokémon Card Binder Manager ✨

A virtual Pokémon card binder to manage and showcase your collection! 

This is a local-first, highly visual, glassmorphic double-page digital card album manager. Mirroring a physical 3-ring binder, it features realistic double-page spreads, customizable full-bleed covers (supporting local file uploads, online URLs, and Dex sprites), autocomplete search index matching, collection sheet grids, a tabular list viewer, and optional automated Google Sheets synchronization.

---

## 🌟 Key Features

*   **📖 Realistic Double-Page Binder**: Arranges cards in facing pages (Even numbers on the left, Odd numbers on the right) with a matching grid geometry and an immersive central metallic ring binder spine.
*   **🎨 Personalized Full-Bleed Cover**: Customize the album cover with text, choose from various theme accent colors, and **upload a local photo** (or use any online URL / featured Pokémon sprite) that stretches to fill the front cover with a hover-zoom effect.
*   **✨ Interactive Holographic Pockets**: Displays high-resolution official card artwork from PokeAPI with hover animations, condition color badges, custom notes, and stack count badges for duplicate cards in the same pocket slot.
*   **🔍 Live Autocomplete Search**: Start typing a Pokémon's name or National Dex ID and select from real-time dropdown matching suggestions containing thumbnail sprites.
*   **📍 Placement Suggestions**: Automatically computes recommended page and slot locations depending on sorting modes (National Dex Order vs. Sequential Fill) and displays warnings if a pocket is already occupied.
*   **📊 Spreadsheet Table View**: Browse your collection in a list format, complete with live text searching, condition filtering, and custom sorting options.
*   **☁️ Google Sheets Synchronization**: Automatically updates card rows to any Google Spreadsheet when cards are added or deleted. Works in tandem with service account credentials.

---

## 🛠️ Technology Stack

*   **Backend Server**: Standard Python library (utilizing built-in `http.server`, `base64`, `urllib`, `json`, and `csv` modules). **Zero external package dependencies required to run local server mode!**
*   **Frontend Client**: Vanilla HTML5, Vanilla CSS3 (custom HSL color palette variables, glassmorphism filters, hover transitions, and animations), and Vanilla ES6 JavaScript (async API routing, FileReader uploads, and autocomplete handlers).
*   **CLI Version**: Terminal-based CLI alternative in python supporting Google Sheets sync.

---

## 🚀 Getting Started

Simply double-click **`Pokemon Binder.exe`** in the project folder.
- **How it works**: Spawns the backend server windowlessly in the background and opens the app in a clean, dedicated window using Microsoft Edge's App Mode. On first launch, it will ask if you want to create a Desktop shortcut.
- **Stopping the app**: Simply close the window. The background server automatically detects that the window has been closed (via a client heartbeat) and shuts itself down after 12 seconds to free up port `8000`. You can also click the "Stop Server" button inside the sidebar to shut it down instantly.

---

## 🛠️ Development

If you want to modify the application logic or re-bundle the standalone executable:
1. Install dependencies: `pip install pyinstaller gspread google-auth`
2. Run the PyInstaller command from the project root:
   ```bash
   python -m PyInstaller --onefile --noconsole --name "Pokemon Binder" --icon data/pokeball.ico --add-data "frontend;frontend" --add-data "data;data" --distpath . backend\pokemon_server.py
   ```
- The C# source code for the legacy launcher is still available in the `src/` folder if needed.

---

## ☁️ Google Sheets Sync Setup Guide

To automatically sync your binder rows to a Google Spreadsheet, follow these steps:

1.  **Create a Google Cloud Project**:
    - Open the [Google Cloud Console](https://console.cloud.google.com/).
    - Create a new project (e.g., "Pokemon Binder").
2.  **Enable APIs**:
    - Search for and enable both the **Google Sheets API** and the **Google Drive API**.
3.  **Generate Credentials**:
    - Go to **Credentials** &rarr; click **+ CREATE CREDENTIALS** &rarr; select **Service Account**.
    - Once created, go to the account's **Keys** tab &rarr; click **Add Key** &rarr; **Create new key** &rarr; select **JSON**.
4.  **Install Credentials**:
    - Rename the downloaded JSON file to exactly **`credentials.json`**.
    - **Where to place it**:
        - **Installed Version**: Press `Win + R`, type `%APPDATA%\PokemonBinder\data`, and paste the file there.
        - **Running from Source**: Place it inside the `backend/` folder.
5.  **Configure your Spreadsheet**:
    - Create a blank Google Spreadsheet in Google Drive (e.g., named "Pokemon Binder").
    - Click **Share** and add the Service Account's email address (found inside your `credentials.json` under `client_email`) with **Editor** permissions.
6.  **Activate Sync**:
    - Open the Pokémon Binder settings, enable **Google Sheets Synchronization**, and enter the **Spreadsheet Name** exactly as it appears in Google Drive.
