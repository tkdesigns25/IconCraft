# IconCraft

Change the icon of *any* specific app on your Android phone (like Letterboxd, Spotify, or Instagram) using your own images. No subscriptions, no ads, and all your other apps stay untouched.

### 👉 [**Download Latest APK from GitHub Releases**](../../releases/latest)

---

## Screenshots

> _Screenshots coming soon! Drop your home screen and app screenshots here._

<!--
| Home Screen | Icon Studio | Apply Guide | Auto-Theme Lab |
| :---: | :---: | :---: | :---: |
| ![Home](docs/screenshots/home.png) | ![Studio](docs/screenshots/studio.png) | ![Apply](docs/screenshots/apply.png) | ![Lab](docs/screenshots/lab.png) |
-->

---

## What It Can Do

- **Change only the apps you pick:** Swap just Letterboxd (or a few favorite apps) while keeping the rest of your phone completely normal.
- **Keep your current icon pack:** Already using an icon pack you love? IconCraft can merge your custom icons with your existing pack so you don't lose your home screen style.
- **Easy built-in editor:** Crop, zoom, adjust padding, round corners, and pick background colors without needing another editing app.
- **Auto-Theme Lab (Experimental):** Upload 1 movie poster or wallpaper and instantly match *all* your apps to its colors and style (Poster Texture, Neon Glow, Minimal) — runs 100% on your phone with zero AI subscriptions.
- **1×1 Home Widget mode:** Even on phones that block custom icon packs (like Google Pixel), drop clean custom app icons onto your Home Screen with no ugly watermarks.

---

## Will It Work on My Phone?

- **Works natively (no extra launcher needed):** OnePlus, Oppo, Realme, Nothing Phone, Samsung (using Good Lock → Theme Park), and newer Xiaomi (HyperOS 3+).
- **Works on any Android phone** if you use a custom launcher like Nova Launcher, Lawnchair, or Smart Launcher.
- **Google Pixel / Motorola / Stock Android:** Use the built-in **1×1 Widget** option (changes Home Screen icons, though App Drawer icons stay default) or use a custom launcher.
- **iPhone (iOS):** Not supported (Android only).

---

## How to Use It

1. Download and install the latest `.apk` from [**Releases**](../../releases/latest).
2. Open IconCraft, tap **Customize an App**, pick your app (e.g., Letterboxd), and choose any photo from your gallery.
3. Tap **Build & Install Icon Pack** — Android will ask you to install the personal icon pack your phone just created.
4. Follow the 2-step guide shown on screen for your phone brand to apply **IconCraft Pack**.

---

## Is It Safe? / Why Does Play Protect Warn Me?

- **100% Offline:** IconCraft doesn't even have permission to access the internet. Your photos and app list never leave your phone.
- **Why Android shows a warning:** Because the app isn't on the Google Play Store and builds your custom icon pack directly on your device, Android/Play Protect shows a standard "Unknown app" prompt.
- **Why it asks to see installed apps & install packages:** Only so you can pick which app's icon to change and install the custom icon pack you just made.

---

## For Developers (Build from Source)

<details>
<summary><strong>Click to expand build instructions & technical architecture</strong></summary>

### Prerequisites
- **JDK 17**
- **Android SDK** (`compileSdk 35`, `minSdk 26`)

### Build & Test Commands
The `:app` build automatically compiles `:iconpack_template` (generating 500 transparent `1×1` PNG slots via `:iconpack_template:ensure500TemplateSlots`) and copies the unsigned template APK into `app/src/main/assets/template_iconpack.apk` via `:app:syncTemplateApk`.

```bash
# Run unit tests (verifies binary AXML encoding, 500-slot template capacity, and v1+v2+v3 APK signing)
./gradlew test

# Assemble the debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug
```

### Release Signing (Optional)
To build a signed release APK, create an untracked `keystore.properties` file in the repository root:

```properties
storeFile=your-release-key.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Then run:
```bash
./gradlew assembleRelease
```

### Architecture & Security Notes
- **Zero Network Access**: Does not declare `android.permission.INTERNET`.
- **On-Device APK Patching & Signing**: Patches `assets/appfilter.xml`, `res/xml/appfilter.xml` (binary AXML), `AndroidManifest.xml`, and `res/drawable-nodpi/custom_icon_XXX.png`, then signs on-device with `com.android.tools.build:apksig` (v1 + v2 + v3 schemes).
- **Hardware-Backed Keystore Protection**: Generates a per-device RSA-2048 `.p12` keystore in app-private storage (`filesDir/iconcraft_keystore.p12`), encrypted with a random 256-bit password wrapped by Android Keystore (`AES/GCM/NoPadding`).

</details>

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
