# 📖 Pokémon Card Binder Manager ✨ (v1.5.0-BETA)

A dual-platform virtual Pokémon card ecosystem to manage, sync, and showcase your collection!

This project consists of a **glassmorphic PC Web Application** and a **native Android Companion App**. Both platforms synchronize in real-time via **Firebase**, featuring realistic double-page spreads, AI-powered card scanning, and fully customizable trainer profiles.

---

## 📱 Mobile Companion App (v1.5.0-BETA)

The **Android app** is designed for the modern collector, bringing your binder to your pocket with advanced AI features.

*   **🧠 Gemini AI Card Critic**: Scan any physical Pokémon card (Front & Back) using **Gemini 3.1 Flash Lite**. The AI identifies the Pokémon and expansion set, and provides a **Technical Grade (1-10)** with a breakdown of Centering, Corners, Edges, and Surface.
*   **📍 Real-time Firebase Sync**: Every card you scan on your phone appears instantly in your PC binder.
*   **🎨 Pro Profile Customization**: Set your trainer avatar using your **phone's gallery** with a built-in cropping tool, choose a featured Pokémon, or use an online URL.
*   **📊 Advanced Collection Management**: Sorting by Dex number, Rarity, or Date. Filtering by rarity tiers and deduplication (Show Repeated toggle) matching the PC experience.
*   **✍️ Manual Entry Mode**: Skip the AI and enter card details manually if you're in a hurry or scanning custom cards.

---

## 💻 PC Desktop Application

A highly visual,immersive digital card album manager for your desktop.

*   **📖 Realistic Double-Page Binder**: Arrangements mirroring a physical 3-ring binder with realistic spreads and an immersive central ring spine.
*   **🎨 Customizable Full-Bleed Cover**: Personalize your album with theme colors and custom background images.
*   **✨ Holographic Visual Effects**: Rarity-based animated holographic shimmer sweeps that react to your mouse movement.
*   **🔍 Power Search**: National Dex autocomplete suggestions with thumbnail sprites for lightning-fast card entry.
*   **🔄 Auto-Sync on Refresh**: Automatically pulls new cards from your mobile app whenever you open or refresh the page (with a 5-minute cooldown to save quota).
*   **☁️ Multi-Cloud Support**: Synchronize with **Firebase** for mobile connectivity and **Google Sheets** for spreadsheet-style management.

---

## 🚀 Getting Started

### Windows Installation
1.  Download **`Pokemon_Binder_Setup.exe`** from the latest [**Releases**](https://github.com/compileERezaCheng/Pokemon-Binder/releases).
2.  Run the setup and follow the instructions.
3.  Launch the app and go to **Settings** to link your Firebase account for mobile syncing.

### Android Installation
1.  Download **`Poke_Binder_mobile.apk`** from the latest [**Releases**](https://github.com/compileERezaCheng/Pokemon-Binder/releases).
2.  Install the APK on your Android device (ensure "Install from Unknown Sources" is enabled).
3.  Sign in with the same Firebase account used on PC to start syncing.

---

## 🛠️ Technology Stack

*   **PC Backend**: Standard Python 3.14 (utilizing `http.server`, `base64`, `urllib`, `json`, and `csv`).
*   **PC Frontend**: Vanilla HTML5, CSS3 (Glassmorphism, CSS Animations), and ES6 JavaScript.
*   **Mobile App**: Native Android (Kotlin + Jetpack Compose), CameraX, and **Google Generative AI SDK**.
*   **Cloud Infrastructure**: Firebase Realtime Database & Auth, Google Sheets API.

---

## 🛠️ Development & Re-bundling

To modify the PC logic or rebuild the executable:
1. Install dependencies: `pip install pyinstaller gspread google-auth`
2. Run the PyInstaller build:
   ```bash
   python -m PyInstaller "Pokemon Binder.spec" --noconfirm
   ```
3. The resulting `.exe` will be in the `dist/` folder.
