# Shoosh

Quiet, for a while. Shoosh puts your Android phone on silent for a set time, then puts the sound back exactly how it was.

## What it does
- Pick a time with the quick chips (15m, 30m, 1h, 2h) or fine-tune in 5-minute steps (up to 12 hours), then tap **Shoosh**.
- **Your modes**: save your own one-tap presets for places you go regularly, like "Church" for 2 hours. Tap to start, hold to edit or delete, and **+ New mode** to add one.
- Remembers your previous sound setting (ring, vibrate, or Do Not Disturb) and your exact ring and notification volume, and restores them when time is up.
- If you change the sound yourself during the timer, Shoosh leaves your choice alone.
- A quiet notification shows a live countdown with a **Sound back on** button.
- **+15 min** extends a running timer.
- Survives phone restarts.
- Light and dark mode follow your system setting.

Requires Android 8.0 or newer.

## Getting the app onto your phone

### Android Studio
1. Download this folder from Google Drive (it arrives as a zip), unzip it, then **File → Open** the Shoosh folder in Android Studio.
2. Let it finish syncing. The first time, it downloads Gradle and the Android build tools.
3. Plug in your phone (with USB debugging on) and press Run.

## First launch
Android only lets apps switch to silent if you grant **Do Not Disturb access**. Shoosh shows a card with an **Open settings** button. Find Shoosh in the list and switch it on, then come back. Allow notifications too if you want the countdown in your status bar.

## Notes
- Some phones (Xiaomi, Oppo, older Samsungs) aggressively kill background apps. If the sound ever doesn't come back, set Shoosh's battery usage to "Unrestricted" in the phone's app settings.
- The restore is scheduled with an exact alarm, so it fires on the minute even if the phone is asleep.

## Releasing updates (GitHub)
Shoosh checks this repository's GitHub Releases when it opens (at most every 6 hours) and shows an **Update** banner when a newer version is out.

**One-time setup:** add four repository secrets (Settings → Secrets and variables → Actions):
- `KEYSTORE_BASE64`: your keystore file, base64-encoded. In PowerShell:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\dev\keys\shoosh.jks")) | Set-Clipboard`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: the same values you used when signing.

**Each release:** Actions → **Release** → **Run workflow** → type the version (e.g. `1.4`). GitHub builds, signs and publishes `shoosh-<versionCode>.apk`. Versions must go up each time.

Updates only install if they're signed with the same key as the installed app.
Builds from Android Studio don't check for updates unless `shooshUpdateRepo` is set in `gradle.properties`.
Note: Google Play doesn't allow apps to update themselves, so remove the updater for a Play Store build.
