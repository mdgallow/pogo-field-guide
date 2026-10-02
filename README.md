# Pokémon GO Mobile Decision Companion 📱

A high-performance, 100% offline-capable field companion web application for Pokémon GO players.

## 🚀 Live App
Visit: **[https://mdgallow.github.io/pogo-field-guide/](https://mdgallow.github.io/pogo-field-guide/)**

## 📲 How to Install ("Approve & Open")

### 🍏 iPhone (iOS Safari)
1. Open the live link in Safari.
2. Tap the **Share** button (box with an arrow pointing up).
3. Tap **Add to Home Screen** ➕.
4. Tap **Add** in the top right.
5. The **PoGo Guide** icon is now installed on your home screen! Open it anytime — it runs full screen with no browser bars and works 100% offline.

### 🤖 Android (Google Chrome)
1. Open the live link in Chrome.
2. Tap the in-app **📲 Install App** button (or tap the 3-dot menu and select **Install App / Add to Home screen**).
3. Tap **Install** to approve.
4. The app is installed into your app drawer and home screen. Works 100% offline!

## 🤖 Native Android APK (floating pill overlay)
The APK wraps the same `index.html` in a WebView and adds what a browser can't do: a pill that floats over Pokémon GO. The website itself is the reference guide (Pokédex, CP/IV tables, filters); scanning lives in the APK.

- **▶ MINIMIZE TO PILL** hides the app behind the pill. *Display over other apps* is granted once; Android's own screen-capture dialog can't be pre-approved, so it is one "Start now" tap per play session, with a plain-language explainer the first time. The pill docks right (drag it across the screen to dock left), drags vertically, and only exists while the app is minimized. **OPEN** brings the app back, **CLOSE** stops it.
- **SCAN** blinks the pill out, captures one frame from the mirror held by `FloatingOverlayService`, runs ML Kit OCR plus pixel detectors (appraisal bars, favorite star, shadow haze, Dynamax badge) on-device, and hands the text lines to `assessNativeOcr()` in the page, which owns the Pokédex data and the verdict rules; the verdict returns through `AndroidBridge.updatePillEx()`.
- **AUTO** keeps reading while you swipe through the appraisal view (the card is fingerprinted about three times a second with the pill left on screen; when a new card has settled the pill blinks once, the frame is read, and a short buzz confirms it) and stops by itself when idle, off the storage page, or after 10 minutes. **GONE** marks the Pokémon on screen as transferred.
- **My Log** (in the app) remembers every scanned Pokémon on the device: identity is scored across family, size, CP, IVs, catch date and place; appraised IVs are kept; RAID / PVP lanes with keep-N and quality floors decide keep / trade / transfer.
- Move data: `python tools/build_moves.py` rebuilds every species' raid and league movesets (with Elite-TM flags) inside `index.html` from the game master and PvPoke; run it when moves are rebalanced. The app itself never downloads anything.
- Build: `android/app/src/main/assets/index.html` is not committed; Gradle's `syncWebAssets` copies the repo-root file on every build. `version.json` is the single version source: `python tools/release.py 2.4.0 "notes"` bumps it and stamps the page and service worker, Gradle reads it, and CI runs `tools/release.py --check` plus `node tests/run.js` before every build. CI signs with the permanent key from the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets (`tools/setup-signing.sh`); the deploy job refuses to run without them.

## ⚡ Features
- **1,401 Pokémon Complete Dataset**: All Gen 1 to 9 Pokémon (#1 to #1025) plus all Alolan, Galarian, Hisuian, and Paldean regional forms.
- **🎯 Catch Screen CP Inspector**: Type any wild CP on the catch screen to instantly check if it can be a Level 1–35 100% IV (Hundo).
- **📋 0-2★ Action Matrix**: Instant tactical guidance for non-3-star catches (Lucky Re-roll, XL Candy Priority, Free Evolution, Transfer).
- **🍇 Recommended Field Berry**: Field catch strategy (Pinap, Silver Pinap, Golden Razz, None) tailored to utility.
- **⚔️ PvP & PvE Movesets**: Top moves from PvPoke and calculated Cycle DPS.
- **🛡️ 18 Type & 10 Region Filters**: Rapid one-tap filtering in the field.
