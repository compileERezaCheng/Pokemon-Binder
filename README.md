# ?? Pokemon Card Binder Manager ?

A dual-platform virtual Pokemon card ecosystem to manage, sync, and showcase your collection!

This project consists of a **glassmorphic PC Web Application** and a **native Android Companion App**. Both platforms can synchronize collections via **Firebase** and have customizable trainer profiles. The PC app also pulls updates when opened or refreshed, subject to a five-minute auto-sync cooldown. Use **Sync** to update the binder and collection list without refreshing the page.

---

## ?? Mobile Companion App

The **Android app** is designed for the modern collector, bringing your binder to your pocket with advanced AI features.

*   **?? Gemini AI Card Critic**: Scan a physical Pok�mon card (Front & Back) using **Gemini**. The AI identifies the Pok�mon and expansion set, and estimates a **Grade (1-10)** with a breakdown of Centering, Corners, Edges, and Surface.
*   **?? Firebase Sync**: Save cards to your account and sync them with the PC binder.
*   **?? Pro Profile Customization**: Set your trainer avatar using your **phone's gallery** with a built-in cropping tool, choose a featured Pok�mon, or use an online URL.
*   **?? Advanced Collection Management**: Sorting by Dex number, Rarity, or Date. Filtering by rarity tiers and deduplication (Show Repeated toggle) matching the PC experience.
*   **??? Searchable Sets and Printings**: Choose a TCGdex printing when adding or grading a card. Sets are sorted alphabetically and can be filtered by set name; searches also include ex and Mega card names.
*   **??? Card Artwork**: Display the chosen printing's artwork, with a placeholder while loading and a safe alternative source when TCGdex artwork is unavailable.
*   **?? Grading Summary and Rank**: Review the condition breakdown and estimated card rank alongside the grade.
*   **?? Manual Entry Mode**: Skip the AI and enter card details manually if you're in a hurry or scanning custom cards.

---

## ?? PC Desktop Application

A visual digital card album manager for your desktop.

*   **?? Realistic Double-Page Binder**: Arrangements mirroring a physical 3-ring binder with realistic spreads and an immersive central ring spine.
*   **?? Customizable Full-Bleed Cover**: Personalize your album with theme colors and custom background images.
*   **? Holographic Visual Effects**: Rarity-based animated holographic shimmer sweeps that react to your mouse movement.
*   **?? Power Search**: National Dex autocomplete suggestions with thumbnail sprites for lightning-fast card entry.
*   **?? Expanded Card Details**: Larger card artwork with click-to-enlarge, editable copy information and notes, rarity effects, and the grade displayed inside the rank symbol.
*   **??? Compact Edition Picker**: View the saved edition first, then open an image grid to change it. Filter alphabetically sorted sets by name without repeated Pok�mon-name prefixes. A pending choice remains separate from the saved edition until saved, and cancelling preserves the saved edition.
*   **?? Auto-Sync on Refresh**: Automatically pulls new cards from your mobile app whenever you open or refresh the page (with a 5-minute cooldown to save quota).
*   **?? Multi-Cloud Support**: Synchronize with **Firebase** for mobile connectivity and **Google Sheets** for spreadsheet-style management.

### Card languages and regional forms

Cards retain their printed name, base species, regional form, physical language and catalog printing as separate data. Regional forms such as Hisuian, Alolan, Galarian and Paldean share the base species' National Dex number for binder placement. Their form and printing remain distinct when saved and synchronized.

On PC, **Add Pokemon** keeps identification to **Pokemon**, **Form** and **Physical card language**. The printing picker has its own catalog-language selector: changing the physical language does not clear the selected printing or change the search language. English species names can resolve to localized catalog names, including Japanese, without requiring the user to type the translated name. Coverage depends on the available catalog data.

From desktop card details, **Ver precos** closes the details dialog and opens Market Prices for the saved catalog locale and printing ID when available. If the card has no confirmed printing, it opens a prefilled search instead.

### Card image fallback

TCGdex remains the primary image source on PC and Android. When its image is missing or fails, the apps query **Scrydex** and accept an image only when the set name, collector number, English language, and card variant identify one result. The matched card ID and image source are retained with saved card data. Catalog IDs are not treated as interchangeable.

If no safe match exists, the app keeps the **"Imagem indispon�vel"** placeholder instead of using another printing's image. Image space remains reserved while loading. The PC details view can show a Pok�mon image as a fallback, but only card artwork can be enlarged.

The Scrydex API can be called without credentials, with reduced rate limits. For higher PC API limits, set `SCRYDEX_API_KEY` and `SCRYDEX_TEAM_ID` in the Windows environment before starting the app; keep both values out of source control and the Android APK. The Android app never embeds these credentials. Scrydex notes that Pok�mon card artwork is third-party content; see its [terms](https://scrydex.com/terms) and [authentication guide](https://scrydex.com/docs/getting-started/authentication).

---

## ?? Getting Started

### Windows Installation

1.  Download **`PokeBinder-Beta-Windows-0.2.0.exe`** from the corresponding [**GitHub Release**](https://github.com/compileERezaCheng/Pokemon-Binder/releases).
2.  Run the setup and follow the instructions.
3.  Launch the app and go to **Settings** to link your Firebase account for mobile syncing.

### Android Installation

The current signed beta package is **`PokeBinder-Beta-Android-v4.apk`** (Android version 1.3, version code 4). It requires **Android 7.0 or newer** and uses the separate application ID `com.example.pokemongrader.beta`.

1. Download `PokeBinder-Beta-Android-v4.apk` from the corresponding [**GitHub Release**](https://github.com/compileERezaCheng/Pokemon-Binder/releases). Installers and APKs are release attachments, not repository source files.
2. Install the APK on your Android device, allowing installation from the browser or file manager when prompted.
3. Sign in with the same Firebase account used on PC, then refresh the PC binder to pull the collection.

Update existing beta installations in place without uninstalling first. The beta uses a separate installation from the regular app. Old test APKs, unsigned/aligned APKs and instrumented test packages are not the current distributable beta.

---

## ??? Technology Stack

*   **PC Backend**: Python (utilizing `http.server`, `base64`, `urllib`, `json`, and `csv`).
*   **PC Frontend**: Vanilla HTML5, CSS3 (Glassmorphism, CSS Animations), and ES6 JavaScript.
*   **Mobile App**: Native Android (Kotlin + Jetpack Compose), CameraX, and **Firebase AI Logic**, plus direct Gemini API requests for personal keys.
*   **Cloud Infrastructure**: Firebase Realtime Database & Auth, Google Sheets API.

---

## ??? Development & Re-bundling

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

Source builds have no release signing configuration by default: `assembleRelease` creates `app-release-unsigned.apk`, which must be signed before installation. The published beta APK is signed separately with the existing beta key and uses the beta application ID. Preserve that signing key for subsequent beta updates; keystores and their passwords are not part of this repository.

Keep local configuration such as `gradingAPP/secrets.properties`, signing keystores, passwords, and API credentials out of commits. Attach distributable APKs and installers to **GitHub Releases** rather than committing generated binaries.

### Firebase sync and optional AI scanner

Use a separate Firebase project for a test collection. Enable **Email/Password** sign-in, create a **Realtime Database** in locked mode, and apply [`database.rules.json`](database.rules.json) to that project. The rules isolate each account and make the legacy collection path read-only. Upgrade both clients before applying these rules to a project used by older versions, and back up existing collections first.

For Android, set `ENC_FIREBASE_API_KEY` and `ENC_FIREBASE_DB_URL` in the local `gradingAPP/secrets.properties` using the project's existing `binder/backend/obfuscator.py` helper. Use the same Firebase project on Windows and Android. Login, sync, Market Prices, and manual card entry work without the Gemini scanner. Android currently keeps the collection in memory between remote syncs; persistent offline collection storage is separate follow-up work.

On Android, open **Profile  Account Settings  Use my Gemini key**. Create a Gemini authorization API key in [Google AI Studio](https://aistudio.google.com/apikey) under your own Google Cloud project with billing enabled. Identification sends the front photo to Google; the separate condition estimate sends both photos. Usage is billed to the key's project. The app encrypts the key with Android Keystore and saves it only on this device, outside backups, for the signed-in PokeBinder account. The same account can use it after reopening the app or signing back in. Use **Remove my Gemini key** to delete the saved key. The key is never synced to Firebase. Manual entry is available without it.

For builds with a shared scanner, configure [Firebase AI Logic with App Check](https://firebase.google.com/docs/ai-logic/app-check), add `FIREBASE_APP_ID` and `FIREBASE_PROJECT_ID` locally, and check the provider's billing requirements and regional terms. A personal key takes precedence while it is active. Never embed a Gemini API key in the app.

### Market Prices (PC and Android)

Open **Market Prices** in the PC sidebar or the Android bottom bar. Search a card name, filter by set and collector number, and explicitly select a printing. Older collection entries can be identified here without modifying the saved card. The bottom bar is **Binder | Collection/Search | + | Market Prices | avatar**; the central action clears slot prefill, while an empty binder slot preserves it. Tap the centred page indicator marked **Sync** to refresh the binder.

On PC, results show artwork, set/number and a price preview before selection. The set dropdown lists the editions found for the current name. Prices are checked for **12 displayed editions at a time**, with at most **three concurrent requests**; **Show more editions** loads another batch. Selecting a result opens the adjacent price panel, with a finish/currency/source selector, one primary quote (EUR preferred) and the other metrics underneath. On narrow screens, selection scrolls to that panel. A missing quote and a failed request have distinct messages; refresh/retry controls appear only when useful. Refreshing a selected price bypasses its normal cache TTL while preserving rate-limit cooldowns.

Search and price lookups use the selected TCGdex catalog locale independently on PC and Android; the physical card language remains a separate field. Supported locale codes are en, fr, es, it, pt, pt-br, pt-pt, de, nl, pl, ru, ja, ko, zh-tw, id, th, and zh-cn. No additional key, service, PC connection or currency conversion is required. The [official pricing guide](https://tcgdex.dev/markets-prices) and [card schema](https://tcgdex.dev/reference/card) describe Cardmarket EUR averages/trends/lows and TCGplayer USD market/median/low values. Each reference displays its own type, finish, currency, provider and source timestamp; missing timestamps are labelled **Consulted**. Missing prices are unavailable, never zero-filled. Marketplace language and condition are **unspecified**, and references are for **uncertified** cards, independent of the AI grade. Translation, image and price coverage varies by locale and printing.

For legacy collections, a saved TCGdex Catalog Card ID is evidence for the old TCGdex English catalog context, so migration fills missing provider/locale as TCGdex/en while leaving physical Card Language unknown. Without a catalog card ID, the migration does not guess either value.

Detailed variant pricing is used when supplied. If a marketplace has no usable variant quote, its root-level price remains visible separately as an **Edition reference**, with **finish unconfirmed**; it is never assigned to a named detailed variant. Zero/non-positive values are treated as absent quotes. Cardmarket's foil group does not identify an exact holo/reverse finish; it is labelled **finish unspecified**. Select the matching printing and finish before interpreting any reference. No Scrydex/image-match IDs or Pok�dex numbers are used for prices. The [TCGdex FAQ](https://tcgdex.dev/faq) acknowledges imperfect marketplace mappings; these are reference prices, not a condition-specific appraisal or guaranteed sale value.

The in-memory cache lasts **one hour**, holds at most **128 complete responses** per platform, and includes the full identity (catalog locale + catalog card ID) and **all** supplied variants, currencies and conditions in each cached response. Search responses include their query in the key. Identical concurrent requests are reused (Android serializes cache access); prices are never persisted to the collection or Firebase. Failed refreshes keep previous responses with an **Outdated cached** label. Search uses **350 ms debounce** and invalidates previous requests immediately. Timeouts are 10 seconds on PC and 5 seconds each for Android connection/read; HTTP 429 triggers a provider cooldown using numeric `Retry-After`, or 60 seconds if absent. Retry remains available after errors. The cache resets when the browser/app process closes.

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
