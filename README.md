# 📖 Pokemon Card Binder Manager ✨

A dual-platform virtual Pokemon card ecosystem to manage, sync, and showcase your collection!

This project consists of a **glassmorphic PC Web Application** and a **native Android Companion App**. Both platforms can synchronize collections via **Firebase** and have customizable trainer profiles. The PC app also pulls updates when opened or refreshed, subject to a five-minute auto-sync cooldown. Use **Sync** to update the binder and collection list without refreshing the page.

---

## 📱 Mobile Companion App

The **Android app** is designed for the modern collector, bringing your binder to your pocket with advanced AI features.

*   **🧠 Gemini AI Card Critic**: Scan a physical Pokémon card (Front & Back) using **Gemini**. The AI identifies the Pokémon and expansion set, and estimates a **Grade (1-10)** with a breakdown of Centering, Corners, Edges, and Surface.
*   **📍 Firebase Sync**: Save cards to your account and sync them with the PC binder.
*   **🎨 Pro Profile Customization**: Set your trainer avatar using your **phone's gallery** with a built-in cropping tool, choose a featured Pokémon, or use an online URL.
*   **📊 Advanced Collection Management**: Sorting by Dex number, Rarity, or Date. Filtering by rarity tiers and deduplication (Show Repeated toggle) matching the PC experience.
*   **🗂️ Searchable Sets and Printings**: Choose a TCGdex printing when adding or grading a card. Sets are sorted alphabetically and can be filtered by set name; searches also include ex and Mega card names.
*   **🖼️ Card Artwork**: Display the chosen printing's artwork, with a placeholder while loading and a safe alternative source when TCGdex artwork is unavailable.
*   **🏅 Grading Summary and Rank**: Review the condition breakdown and estimated card rank alongside the grade.
*   **✍️ Manual Entry Mode**: Skip the AI and enter card details manually if you're in a hurry or scanning custom cards.

---

## 💻 PC Desktop Application

A visual digital card album manager for your desktop.

*   **📖 Realistic Double-Page Binder**: Arrangements mirroring a physical 3-ring binder with realistic spreads and an immersive central ring spine.
*   **🎨 Customizable Full-Bleed Cover**: Personalize your album with theme colors and custom background images.
*   **✨ Holographic Visual Effects**: Rarity-based animated holographic shimmer sweeps that react to your mouse movement.
*   **🔍 Power Search**: National Dex autocomplete suggestions with thumbnail sprites for lightning-fast card entry.
*   **🃏 Expanded Card Details**: Larger card artwork with click-to-enlarge, editable copy information and notes, rarity effects, and the grade displayed inside the rank symbol.
*   **🗂️ Compact Edition Picker**: View the saved edition first, then open an image grid to change it. Filter alphabetically sorted sets by name without repeated Pokémon-name prefixes. A pending choice remains separate from the saved edition until saved, and cancelling preserves the saved edition.
*   **🔄 Auto-Sync on Refresh**: Automatically pulls new cards from your mobile app whenever you open or refresh the page (with a 5-minute cooldown to save quota).
*   **☁️ Multi-Cloud Support**: Synchronize with **Firebase** for mobile connectivity and **Google Sheets** for spreadsheet-style management.

### Card image fallback

TCGdex remains the primary image source on PC and Android. When its image is missing or fails, the apps query **Scrydex** and accept an image only when the set name, collector number, English language, and card variant identify one result. The matched card ID and image source are retained with saved card data. Catalog IDs are not treated as interchangeable.

If no safe match exists, the app keeps the **“Imagem indisponível”** placeholder instead of using another printing's image. Image space remains reserved while loading. The PC details view can show a Pokémon image as a fallback, but only card artwork can be enlarged.

The Scrydex API can be called without credentials, with reduced rate limits. For higher PC API limits, set `SCRYDEX_API_KEY` and `SCRYDEX_TEAM_ID` in the Windows environment before starting the app; keep both values out of source control and the Android APK. The Android app never embeds these credentials. Scrydex notes that Pokémon card artwork is third-party content; see its [terms](https://scrydex.com/terms) and [authentication guide](https://scrydex.com/docs/getting-started/authentication).

---

## 🚀 Getting Started

### Windows Installation

1.  Download **`Pokemon_Binder_Setup.exe`** from the latest [**Releases**](https://github.com/compileERezaCheng/Pokemon-Binder/releases).
2.  Run the setup and follow the instructions.
3.  Launch the app and go to **Settings** to link your Firebase account for mobile syncing.

### Android Installation

The currently prepared test package is **`app-release-test.apk`**. It is signed with a development key and can be installed without creating a production signing key. It requires **Android 7.0 or newer**.

1. Download `app-release-test.apk` from a [**GitHub Release**](https://github.com/compileERezaCheng/Pokemon-Binder/releases) when attached there. The locally prepared file is at `gradingAPP/app/build/outputs/apk/release/app-release-test.apk`; generated APKs are not committed to the repository.
2. Install the APK on your Android device, allowing installation from the browser or file manager when prompted.
3. Sign in with the same Firebase account used on PC, then refresh the PC binder to pull the collection.

The legacy **`Poke_Binder_mobile.apk`** in the project root is older and does not contain the latest changes. Use `app-release-test.apk` for the current test build. `app-release-unsigned.apk` is not ready to install; `app-debug-androidTest.apk` contains automated tests rather than the app.

---

## 🛠️ Technology Stack

*   **PC Backend**: Python (utilizing `http.server`, `base64`, `urllib`, `json`, and `csv`).
*   **PC Frontend**: Vanilla HTML5, CSS3 (Glassmorphism, CSS Animations), and ES6 JavaScript.
*   **Mobile App**: Native Android (Kotlin + Jetpack Compose), CameraX, and **Google Generative AI SDK**.
*   **Cloud Infrastructure**: Firebase Realtime Database & Auth, Google Sheets API.

---

## 🛠️ Development & Re-bundling

### Windows executable and installer

From the repository root, install the Python build dependencies and rebuild both Windows executables:

```powershell
python -m pip install pyinstaller gspread google-auth
$env:POKEBINDER_FIREBASE_API_KEY = '<Firebase client API key>'
$env:POKEBINDER_FIREBASE_DB_URL = '<Realtime Database HTTPS URL>'
.\binder\build.ps1
```

The desktop app and its local server are generated in `binder/dist/`. The installer starts the local server and opens the binder in the default browser. Compile **`binder/setup_script.iss`** in **Inno Setup** to generate `binder/Pokemon_Binder_Setup.exe`.

Always rebuild the executable before compiling the installer so that the package includes the latest frontend and backend changes.

### Android test APK

Open `gradingAPP/` in Android Studio with the Android SDK installed, or run the Gradle wrapper from that directory:

```powershell
.\gradlew.bat assembleDebug
```

The installable development APK is generated at `gradingAPP/app/build/outputs/apk/debug/app-debug.apk`. This command does not require a production signing key.

The release build currently has no production signing configuration: `assembleRelease` creates `app-release-unsigned.apk`. The separately prepared `app-release-test.apk` uses a development key and is intended for testing. A production release still needs a dedicated signing configuration.

Keep local configuration such as `gradingAPP/secrets.properties`, signing keystores, passwords, and API credentials out of commits. Attach distributable APKs and installers to **GitHub Releases** rather than committing generated binaries.

### Firebase sync and optional AI scanner

Use a separate Firebase project for a test collection. Enable **Email/Password** sign-in, create a **Realtime Database** in locked mode, and apply [`database.rules.json`](database.rules.json) to that project. The rules isolate each account and make the legacy collection path read-only. Upgrade both clients before applying these rules to a project used by older versions, and back up existing collections first.

For Android, set `ENC_FIREBASE_API_KEY` and `ENC_FIREBASE_DB_URL` in the local `gradingAPP/secrets.properties` using the project's existing `binder/backend/obfuscator.py` helper. Use the same Firebase project on Windows and Android. Login, sync, Market Prices, and manual card entry work without the Gemini scanner.

To enable the optional scanner, configure [Firebase AI Logic with App Check](https://firebase.google.com/docs/ai-logic/app-check), add `FIREBASE_APP_ID` and `FIREBASE_PROJECT_ID` locally, and check the provider's billing requirements and regional terms. Never embed a Gemini API key in the app.

### Market Prices (PC and Android)

Open **Market Prices** in the PC sidebar or the Android bottom bar. Search a card name, filter by set and collector number, and explicitly select a printing. Older collection entries can be identified here without modifying the saved card. The bottom bar is **Binder | Collection/Search | + | Market Prices | avatar**; the central action clears slot prefill, while an empty binder slot preserves it. Tap the centred page indicator marked **Sync** to refresh the binder.

On PC, results show artwork, set/number and a price preview before selection. The set dropdown lists the editions found for the current name. Prices are checked for **12 displayed editions at a time**, with at most **three concurrent requests**; **Show more editions** loads another batch. Selecting a result opens the adjacent price panel, with a finish/currency/source selector, one primary quote (EUR preferred) and the other metrics underneath. On narrow screens, selection scrolls to that panel. A missing quote and a failed request have distinct messages; refresh/retry controls appear only when useful. Refreshing a selected price bypasses its normal cache TTL while preserving rate-limit cooldowns.

Prices come directly from the existing **TCGdex English catalog**, independently on each platform: no additional key, service, PC connection or currency conversion is required. The [official pricing guide](https://tcgdex.dev/markets-prices) and [card schema](https://tcgdex.dev/reference/card) describe Cardmarket EUR averages/trends/lows and TCGplayer USD market/median/low values. Each reference displays its own type, finish, currency, provider and source timestamp; missing timestamps are labelled **Consulted**. Missing prices are unavailable, never zero-filled. English identifies the catalog language; marketplace language and condition are **unspecified**, and references are for **uncertified** cards, independent of the AI grade.

Detailed variant pricing is used when supplied. If a marketplace has no usable variant quote, its root-level price remains visible separately as an **Edition reference**, with **finish unconfirmed**; it is never assigned to a named detailed variant. Zero/non-positive values are treated as absent quotes. Cardmarket's foil group does not identify an exact holo/reverse finish; it is labelled **finish unspecified**. Select the matching printing and finish before interpreting any reference. No Scrydex/image-match IDs or Pokédex numbers are used for prices. The [TCGdex FAQ](https://tcgdex.dev/faq) acknowledges imperfect marketplace mappings; these are reference prices, not a condition-specific appraisal or guaranteed sale value.

The in-memory cache lasts **one hour**, holds at most **128 complete responses** per platform, and includes the full identity (English endpoint + catalog card ID) and **all** supplied variants, currencies and conditions in each cached response. Search responses include their query in the key. Identical concurrent requests are reused (Android serializes cache access); prices are never persisted to the collection or Firebase. Failed refreshes keep previous responses with an **Outdated cached** label. Search uses **350 ms debounce** and invalidates previous requests immediately. Timeouts are 10 seconds on PC and 5 seconds each for Android connection/read; HTTP 429 triggers a provider cooldown using numeric `Retry-After`, or 60 seconds if absent. Retry remains available after errors. The cache resets when the browser/app process closes.

TCGdex needs [no authentication and publishes no hard rate limit](https://tcgdex.dev/faq); caching is recommended. Scrydex was evaluated: its [prices](https://scrydex.com/docs/getting-started/prices) include condition-specific `market`/`low` references in USD/JPY (`include=prices`), while EUR coverage is still planned. Its current [authentication](https://scrydex.com/docs/getting-started/authentication) and [rate limits](https://scrydex.com/docs/getting-started/rate-limits) require private credentials/plan quotas (100 requests/second), so it is not used for this feature. Provider names remain visible; dataset licence attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Artwork and marketplace trademarks remain with their respective owners.

Android supports portrait and landscape. The binder fits all nine slots without vertical scrolling; collection, prices and details retain scrolling. The first binder page has no previous-page control.

### Running automated checks

Run the PC checks from the repository root:

```powershell
node --test binder/tests/frontend.test.js
python -m unittest discover -s binder/tests
```

Run Android unit tests and compile the device tests from `gradingAPP/`:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebugAndroidTest
```

Run the Android instrumented tests on a connected device or emulator through Android Studio. Compiling the test APK alone does not execute those tests.
