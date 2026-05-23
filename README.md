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

### 1. Launching the Web Interface (Recommended)

There are two ways to start the web interface:

#### Option A: Run Silently in the Background (No Terminal Window) ✨
1. Double-click **`run_server.vbs`** in the project folder. This will launch the server completely in the background and open your web browser.
2. To stop the background server at any time, double-click **`stop_server.bat`** in the project folder.

#### Option B: Run with a Visible Terminal Window
1. Double-click **`run_server.bat`** in the project folder.
2. This starts the server inside a command prompt window and opens your browser.
3. To stop the server, press `Ctrl + C` inside the terminal window or close the window.

### 2. Launching the CLI Terminal Version
If you prefer running a command-line script interface, double-click **`run_binder.bat`** to start `pokemon_binder.py`.

---

## ☁️ Google Sheets Sync Setup Guide

To automatically sync your binder rows to a Google Spreadsheet:

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project.
2. Enable both the **Google Sheets API** and **Google Drive API**.
3. Head to **Credentials** &rarr; click **+ CREATE CREDENTIALS** &rarr; select **Service Account**.
4. Inside the created account's **Keys** tab, click **Add Key** &rarr; **Create new key** &rarr; select **JSON**.
5. Save the downloaded credentials file inside the project folder and rename it exactly to **`credentials.json`**.
6. Create a blank Google Spreadsheet in Google Drive (e.g. named "Pokemon Binder").
7. Click **Share** in the Google Sheet and add the Service Account's email address (found inside `credentials.json` under `client_email`) with **Editor** permissions.
8. Activate "Google Sheets Sync" inside the settings panel of the web app or CLI.
