# Release Notes: v1.5.1

This release delivers major usability upgrades, layout bugfixes, and aesthetic refinements to the Pokémon Binder companion app and PC version. It transitions grading functionality to an on-demand flow and resolves camera layout issues.

---

## 🌟 What's New in v1.5.1

### 🛠️ Key Improvements & Fixes

1. **User-Requested AI Grading**:
   - *Change*: Removed automatically-triggered grading on initial camera scan (which was slow and prone to errors).
   - *Feature*: Introduced a prominent **"GRADE THIS CARD WITH AI (BETA)"** button on the confirmation sheet. Scanner scans name, dex number, and rarity first; user triggers the full front/back condition critique only when requested.

2. **Removed Expansion Sets**:
   - *Correction*: Dropped expansion set mapping, dropdowns, and textfields entirely from the mobile database submissions and UI. This prevents the Gemini scanner from outputting incorrect set names (which formerly led to low grading scores).

3. **Auto-Prefill Page and Slot Coordinates**:
   - *Feature*: Automatically calculates and suggests the correct page and slot position in the binder when the name or Dex ID resolves (e.g., Dex #25 resolves page 3, slot 7), matching the PC behavior.

4. **Cleaner App Launcher Icon**:
   - *Design*: Replaced the default icon with a high-contrast flat binder design featuring a classic red-and-white Pokeball on the cover. Removed all transparent checkerboard artifacts in favor of a solid black background.

5. **Layout Compilation Fix**:
   - *Fix*: Resolved a Kotlin compile syntax error (`e: ScanScreen.kt:1154:2 Syntax error: Expecting '}'`) caused by misaligned braces inside layout blocks.

---

## 📦 Distribution Packages

* **Mobile App APK**: [Poke_Binder_mobile.apk](file:///C:/Users/dr4g0/OneDrive/Ambiente%20de%20Trabalho/Dr4g0nTom/VsStudio/pokebinder/Poke_Binder_mobile.apk)
* **PC Setup Installer**: [Pokemon_Binder_Setup.exe](file:///C:/Users/dr4g0/OneDrive/Ambiente%20de%20Trabalho/Dr4g0nTom/VsStudio/pokebinder/binder/Pokemon_Binder_Setup.exe)
