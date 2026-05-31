# 📖 Project Context: Pokémon Binder Manager (v1.5.1)

This document provides a technical overview of the project's architecture, state, and workflows. Use this as a reference for future development or troubleshooting.

---

## 🏗️ Architecture Overview

The application is a **local-first web application** (PC) paired with a **native Android companion app** (Mobile). Both sync via Firebase Realtime Database.

### 1. Backend (Python 3.14+)
- **Core Script**: `backend/pokemon_binder.py` (CLI and business logic)
- **Server Script**: `backend/pokemon_server.py` (HTTP API entry point for the Web App)
- **Technology**: Standard Python libraries (`http.server`, `urllib`, `json`, `csv`). No external runtime dependencies for basic local operation. Firebase sync requires the `firebase-admin` or `requests` library.
- **Server Behavior**: Runs a local server on port `8080`. Uses a **client heartbeat** mechanism; if the frontend window is closed, the server automatically shuts down after 15 seconds of inactivity.

### 2. Frontend (Vanilla JS/HTML/CSS)
- **Location**: `frontend/`
- **Design**: Glassmorphic, responsive, double-page binder layout with rarity-based holographic shimmer effects on cards.
- **Communication**: REST-like API (POST/GET requests) against the Python backend.

### 3. Mobile App (Android / Kotlin Compose)
- **Location**: `gradingAPP/`
- **Technology**: Kotlin + Jetpack Compose, Firebase Auth + Realtime Database, CameraX, **Gemini AI SDK**.
- **Sync**: Bidirectional sync with Firebase Realtime Database; mirrors the PC collection. Exposes silent session token refreshing on app resume to prevent empty collections.

---

## 📂 Data Persistence & Paths

Data is stored persistently to survive updates and remain writable even when installed in `Program Files`.

| File | Purpose | Location (Installed) |
| :--- | :--- | :--- |
| `pokemon_binder.csv` | Your card collection data | `%APPDATA%\PokemonBinder\data\` |
| `binder_config.json` | App settings (grid size, sync settings, Firebase credentials) | `%APPDATA%\PokemonBinder\data\` |
| `credentials.json` | Google Sheets API Service Account key | `%APPDATA%\PokemonBinder\data\` |
| `cover_image.png` | Custom binder cover image | `%APPDATA%\PokemonBinder\data\` |
| `pokemon_cache.json` | Cached PokeAPI species data | `%APPDATA%\PokemonBinder\data\` |
| `sync_state.json` | Last known sync state for 3-way merge | `%APPDATA%\PokemonBinder\data\` |
| `debug_sync.log` | Trace log for sync merge decisions | `%APPDATA%\PokemonBinder\data\` |

---

## ☁️ Firebase Realtime Database Integration

### Authentication
- **PC App**: Email/password login stored in `binder_config.json`. Exposed via `/api/firebase/login` and `/api/firebase/logout` endpoints.
- **Mobile App**: Firebase Auth (email/password) managed natively via the Firebase Android SDK, utilizing automatic silent re-auth (via Google securetoken API) to handle token expiration seamlessly without user logout.

### Firebase User Collection Isolation (Multi-User)
Binders are isolated per-user under `/users/{user_id}/collection.json` in the Firebase Realtime Database:
- **Authentication Security**: Each user authentication (email/password) yields a unique `user_id` (Firebase UID).
- **Data Isolation**: A user's collection is completely private, and changes only affect their specific database node path. User A (`user1`) and User B (`user2`) can maintain entirely independent binders using their respective logins on either PC or mobile.

### Sync Strategy: 3-Way Merge
The sync algorithm uses a **3-way merge** (local state, remote state, last known sync snapshot) to correctly handle:
- Cards added on PC and not yet on mobile, and vice-versa.
- Cards **deleted** on one side (without 3-way merge, deletions would reappear on the next sync).

The `sync_state.json` file stores the last-known collection snapshot used as the "common ancestor" in the merge.

### First-Time Login (Non-Destructive Union Merge)
When a user logs in on the PC for the **first time** (no existing `sync_state.json`), the merge performs a **union** of the local CSV collection and the remote Firebase collection:
- Cards present in either collection are kept.
- For duplicate entries (same identity key), whichever list has more cards (stacks) wins.
- **No cards are ever deleted during the first-time link.** This ensures a safe merge of pre-existing local and cloud collections.

---

## 🃏 Card Rarity Visual Effects

Both the PC app and the mobile app implement rarity-based visual effects. The effects should be consistent across both platforms.

| Rarity Tier | Effect |
| :--- | :--- |
| **Holo Rare** | Shimmer sweep restricted to the artwork frame |
| **Reverse Holo** | Shimmer on outer card border/body, blocked from artwork square |
| **Secret / Hyper / Shiny / Ace / Mega** | Neon borders, custom backing gradients, full-body shimmer sweeps |
| **Illustration / Special Illustration Rare** | Full-bleed artwork behind frosted overlay headers/bodies |
| **Common / Uncommon** | No special effect |

---

## 🧠 AI Grading — "Gemini Card Critic" Strategy

The goal is a **two-stage self-learning grading system** that starts with general AI and graduates to a specialized custom model.

### Stage 1 — Data Collection (Gemini 3.1 Flash / 3.5 Flash)
- The mobile scanner captures a photo of a physical Pokémon card using **CameraX**.
- **Initial Scan**: Evaluates the front image to identify name, dex number, and rarity *only*. Expansion set mapping has been completely dropped to avoid inaccuracies.
- **On-Demand Grading**: Card grading (centering, corners, edges, surface) is done exclusively upon requesting it via the "Grade Card with AI" button on the confirmation screen, using a dual-image prompt (Front + Back).
- **v1.5.1 Gemini Integration**: 
    - Real-time image capture using `ImageCapture` API (1024px resolution).
    - Multi-modal analysis (Front + Back) for accurate condition grading.
    - Detailed grading breakdown `[C, Cr, E, S]` saved in notes.
    - **Grade corner badges** are parsed and displayed on cards directly within the binder layout.
    - **Automatic Coordinate Prefills**: Resolving the Dex ID automatically updates both the page and slot values based on standard binder grid configurations.
    - **Known Issues**:
        - **Non-English Localized Text Appending**: When scanning foreign-language cards (Japanese, Spanish, French, etc.), the AI translates or appends language tags/suffixes (e.g., "(Japanese version)") directly in the name field. The app is unable to resolve this modified name via PokeAPI and fails to identify the correct Dex ID or fetch artwork.
    - **Roadmap / Upgrades (Next Version v1.6.0)**:
        - Implement a localized cleaning utility or AI prompt rule strictly forbidding appended text or language suffixes in the `name` JSON property.
        - Perform name normalization (regex stripping of parenthetical suffixes/non-English annotations) before querying PokeAPI.
        - Implement fallback mapping to resolve Pokémon names back to their standard English names for PokeAPI queries when foreign language cards are scanned.
        - Implement swipe left/right gestures in the mobile binder view to navigate between pages, removing the need for manual "prev" and "next" page buttons.

---

## 🚀 Release History

### [v1.5.1] - 2026-05-31
**Manual Grading, Drop Expansion Sets & Automatic Coordinate Calculations**
- **Refactored Grading**: Removed automatically-triggered grading on initial scan. Introduced on-demand "Grade Card with AI" button.
- **Removed Expansion Sets**: Removed custom set mapping, drop-downs, and input fields entirely from the scanning process and database submissions.
- **Auto coordinate Prefill**: Updated name/dex selection logic to dynamically compute page and slot positions on mobile.

### [v1.5.0] - 2026-05-25
**Real Gemini AI, Autocomplete & Core Mobile Improvements**
- **Mobile Scanner**: Transitioned to **Gemini SDK** models with multi-modal analysis and automatic grade processing.
- **Manual Entry & Autocomplete**: Integrated live autocomplete query mapping from PokeAPI for Pokémon names. Added numerical page dropdown (1-20) and slot dropdown (1-9) which correctly prefill from the selected binder grid pocket coordinate.
- **Live Image Previews**: Display a live card artwork preview under entry confirmation sheets updating reactively as Dex IDs resolve.
- **Improved Collection UI**: Added Pull-To-Refresh swipe-down gesture, resolved dimmed filter/sort buttons contrast, and overlayed compact grade star badges on cards inside the binder.
- **Authentication Resilience**: Implemented background Firebase REST token refresh to fix cards not loading on app resume.
- **Full Rarity Spectrum**: Configured confirmation flow dropdowns to support all 14 application rarities.
- **Profile Customization**: Refactored profile selection into sub-page with gallery upload cropping controls.

### [v1.4.0] - 2026-05-25
**Firebase Sync & Premium Mobile UX**
- **PC Firebase Login**: Added Link/Disconnect account options to Settings with non-destructive union merge logic.
- **Mobile Shimmers**: Rarity-based animated holographic shimmer sweeps matching PC aesthetics.
- **Mobile Card Details**: Dedicated full-page screen for card inspection.
- **Mobile Account Settings**: Confirmation-guarded logout and profile customization.
- **Mobile Scanner UI**: CameraX integration with expansion set verification.

### [v1.2.7] - 2026-05-24
**Show Repeated Toggle, Dex + Rarity Sorting & Readability Improvements**
- **"Show Repeated" Filter**: Deduplicates matching cards.
- **Rarest-First Dex Sorting**: Cards sorted by rarity rank within the same Dex number.
- **Collection Card Count**: Stacks counted per slot.

---

## 🛠️ Future Roadmap

- [x] **v1.1.x Sync Fix**: 3-way merge with permanent card removal support.
- [x] **v1.4.0 Firebase PC Login**: Non-destructive union merge on first sync.
- [x] **v1.4.0 Mobile Premium UX**: Shimmer effects, direct card details, Account Settings, CameraX.
- [x] **v1.5.0 Real Gemini Integration**: Multi-modal vision analysis, autocomplete, prefill, and grade badging.
- [x] **v1.5.1 Manual Grading & Coordinate Prefills**: Drop expansion sets and enable user-triggered AI grading.
- [ ] **v2.0 Custom Grading Model**: Fine-tune a Vision Transformer on the critique dataset (Stage 2).
- [ ] **Multiple Binder Support**: Organize different sets into separate virtual albums.

---
*Last Updated: 2026-05-31 (v1.5.1 — Manual AI Grading & Drop Sets)*
