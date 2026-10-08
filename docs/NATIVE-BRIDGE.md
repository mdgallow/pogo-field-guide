# Native bridge contract

`index.html` is the single brain: Pokédex data, verdict rules, My Log and all wording. A native
shell only captures the screen, reads text and pixels, and shows what the page answers. Keeping
that split is what lets one change reach every platform at once.

The contract below is exercised by `node tests/run.js` (no device needed). A new shell is correct
when it sends the same payloads and renders the same pill states.

## Shell → page

| Call | When | Payload |
|---|---|---|
| `window.assessNativeOcr(payload)` | every manual SCAN and every AUTO read | see below |
| `window.pillAction('gone')` | the user taps GONE on the pill | action name |
| `window.appForegrounded()` | the app's own UI becomes visible again | none |

`assessNativeOcr` payload:

```json
{
  "storage": true,          // pixel check: the white Pokémon-detail card is on screen
  "favorite": false,        // gold favourite star
  "ivs": [15, 14, 15],      // appraisal bars [attack, defense, hp], or null when no panel is open
  "shadow": false,          // dark-violet haze around the body
  "dynamax": false,         // Dynamax badge
  "auto": false,            // true for AUTO reads (no vibration, sweep bookkeeping)
  "lines": [ { "t": "CP3000", "x": 0.34, "y": 0.05, "w": 0.26, "h": 0.04 } ]
}
```

`lines` are OCR text lines with their box as fractions of the frame (0–1, origin top-left).
Lines under the shell's own overlay must be left out.

## Page → shell

All calls go through the `Native` object in `index.html`; nothing else touches a platform object.

| `Native` call | Android implementation | Meaning |
|---|---|---|
| `updatePill(mode, target, slots, actions)` | `AndroidBridge.updatePillEx` (JSON strings) | Show this state. `target` is `"Name\nCP"`, `slots` is `[[caption, value, colour], …]`, `actions` is a list of buttons to offer (`"gone"`). |
| `autoTrail()` | `AndroidBridge.autoTrail` | Text log of the last AUTO session, for My Log's diagnostics. |
| `copyText(text)` | `AndroidBridge.copyText` | Put a search string on the clipboard (the player pastes it into the game). Returns false when the shell has no clipboard call; the page then uses the browser clipboard. |
| `shareDiagnostics(json, viaSheet)` / `testSharing` | `AndroidBridge.shareDiagnostics` | Test builds only: hand the My Log (no places) + AUTO trail bundle to the share sheet; the tester picks the destination. Removed for the public release. |
| `checkForUpdate()` / `canSelfUpdate` | `AndroidBridge.checkForUpdate` | Sideload builds only; a store build sets `canSelfUpdate` false. |

## What stays native (per platform)

- Getting frames (Android: MediaProjection; iOS: a ReplayKit broadcast or a screenshot).
- OCR (Android: ML Kit; iOS: Vision). Only the `lines` format is shared.
- Pixel detectors: appraisal bars, favourite star, shadow haze, Dynamax badge, storage-card check.
  These are the one piece of logic that exists per platform; a port must be validated against
  the same screenshots as `FloatingOverlayService`.
- Showing the result (Android: overlay pill).
- AUTO pacing, stop rules and battery behaviour.

## Version

`version.json` is the only place a version is written. `tools/release.py` bumps it and stamps
`index.html` and `sw.js`; Gradle reads it; CI fails when they disagree.
