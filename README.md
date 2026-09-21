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
The APK wraps the same `index.html` in a WebView and adds what a browser can't do: a pill that floats over Pokémon GO.

- **▶ MINIMIZE TO PILL** (or any Mini Mode / Scan button in the app) hides the app behind the pill. *Display over other apps* is granted once, ever. Android's own screen-capture dialog can't be pre-approved by any app or T&C, so it's held to one "Start now" tap per play session (the grant lives until the pill is closed; on Android 14+ the single-app chooser is skipped), with a plain-language explainer shown the first time only. The pill docks to the right edge, drags vertically, and only exists while the app is minimized — **⛶ EXPAND** brings the full app back and the pill disappears.
- **⚡ SCAN** grabs the current frame from the screen mirror held by `FloatingOverlayService` (the app is never brought forward), runs ML Kit OCR on-device, drops any text under the pill itself, and sends the text lines to the WebView's `assessNativeOcr()`. That shares `classifyScan()` / `applyScanResult()` with the browser's Tesseract path, and the verdict returns to the pill through `AndroidBridge.updatePill()`.
- `android/app/src/main/assets/index.html` is not committed; Gradle's `syncWebAssets` task copies the repo-root file on every build.

## ⚡ Features
- **1,401 Pokémon Complete Dataset**: All Gen 1 to 9 Pokémon (#1 to #1025) plus all Alolan, Galarian, Hisuian, and Paldean regional forms.
- **🎯 Catch Screen CP Inspector**: Type any wild CP on the catch screen to instantly check if it can be a Level 1–35 100% IV (Hundo).
- **📋 0-2★ Action Matrix**: Instant tactical guidance for non-3-star catches (Lucky Re-roll, XL Candy Priority, Free Evolution, Transfer).
- **🍇 Recommended Field Berry**: Field catch strategy (Pinap, Silver Pinap, Golden Razz, None) tailored to utility.
- **⚔️ PvP & PvE Movesets**: Top moves from PvPoke and calculated Cycle DPS.
- **🛡️ 18 Type & 10 Region Filters**: Rapid one-tap filtering in the field.
