# IconCraft — Custom Selective Icon Studio

**IconCraft** is a 100% offline, open-source Android studio built with **Kotlin + Jetpack Compose** that lets you replace the launcher icon of **only the specific apps you choose** (such as *Letterboxd*) with custom `512×512` artwork—without forcing a global shape mask over the rest of your installed apps.

---

## ✨ What IconCraft Does

1. **Selective Custom Icons (Zero Global Masking)**
   - Compiles and signs a real Android Icon Pack APK (`com.iconcraft.pack`) directly on your device in under 2 seconds.
   - When **Base Icon Pack** is set to `"None — Keep System Icons"`, the generated `appfilter.xml` contains **only** `<item component="..." drawable="..." />` mappings for the apps in your queue and omits `<iconback>`, `<iconmask>`, `<iconupon>`, and `<scale>`—keeping every other app on your phone 100% untouched.

2. **Optional Base Icon Pack Inheritance (Merge Mode)**
   - Already using a third-party icon pack? Select any installed icon pack (`org.adw.launcher.THEMES`, `com.novalauncher.THEME`, `com.anddoes.launcher.THEME`) as your **Base Pack** on the Home screen.
   - IconCraft inherits the Base Pack's themed icons and masking rules for up to 500 installed launchable apps while giving your custom IconCraft overrides top priority.

3. **Non-Destructive Single-App Icon Studio**
   - Interactive pan & pinch-to-zoom canvas with outer shape masks (`Rounded Square`, `Square`, `Circle`, `Full Bleed`).
   - Independent inner image scaling (`30%`–`120%`) with optional rounded inner corners over a full-bleed background fill (`No Background`, `androidx.palette` auto-extracted swatches, or interactive canvas eyedropper).
   - Full edit support: reopen any queued icon via the pencil button to tweak zoom, pan, scale, or background without starting over.

4. **Universal 1×1 Home Screen Widget Fallback**
   - Includes a customizable `1×1` Home Screen widget (`CustomIconWidgetProvider`) that launches your target app directly via `PendingIntent`—**with zero Android shortcut corner badge/watermark**.

5. **Experimental Auto-Theme Lab (100% Offline, Zero AI)**
   - Upload a single inspiration image (e.g., a movie poster or wallpaper) to extract its 6-color `Palette` and procedurally generate themed icons across up to 200 installed apps on-device using pure `Bitmap`, `Canvas`, and `ColorMatrix` math (*Poster Texture*, *Neon Glow*, and *Minimal Duotone* styles).

---

## 📱 Device & Launcher Compatibility

| Device / Launcher | Native Selective Icon Pack Support | How to Apply `IconCraft Pack` |
| :--- | :--- | :--- |
| **Nothing OS** (`com.nothing.launcher`) | ✅ Supported natively | Long-press Home Screen → *Customization* → *Icon pack* → Select **IconCraft Pack** |
| **OnePlus / Oppo / Realme** (*OxygenOS / ColorOS / Realme UI*) | ✅ Supported natively | Long-press Home Screen → *Icons* → Select **IconCraft Pack** *(or long-press an individual app → Edit → tap icon)* |
| **Samsung One UI** | ✅ Supported via Good Lock | Open **Good Lock** → **Theme Park** → *Icon* → *Create New* → *Iconpack* → Select **IconCraft Pack** |
| **Xiaomi HyperOS 3+ / MIUI** | ⚠️ Partial / Theme Store | Supported on launchers with external icon pack pickers, or use the **1×1 Home Widget** fallback |
| **Custom Launchers** (*Nova, Lawnchair, Smart Launcher, Niagara, Action, Apex*) | ✅ Supported natively | Launcher Settings → *Look & Feel* / *Icons* → *Icon Pack* → Select **IconCraft Pack** (or per-app edit) |
| **Google Pixel / Motorola / Stock Android** | 🧩 Via 1×1 Widget Fallback | Stock Pixel Launcher does not load third-party icon packs; long-press Home Screen → *Widgets* → **IconCraft → 1×1 Custom Icon** (zero watermark) |

---

## 📸 Screenshots

> _Screenshots coming soon! Drop your screenshots in `docs/screenshots/` and link them here._

<!--
| Home & Queue | Icon Studio | Launcher Apply Guide | Auto-Theme Lab |
| :---: | :---: | :---: | :---: |
| ![Home](docs/screenshots/home.png) | ![Studio](docs/screenshots/studio.png) | ![Apply](docs/screenshots/apply.png) | ![Lab](docs/screenshots/lab.png) |
-->

---

## 🔒 Privacy & Permissions

IconCraft is **100% offline** and **does not request `android.permission.INTERNET`**. No analytics, telemetry, crash reporters, or network calls exist in the codebase.

### Why These Permissions Are Used
- `android.permission.QUERY_ALL_PACKAGES`: Required so IconCraft can list your installed launchable apps (`ACTION_MAIN` + `CATEGORY_LAUNCHER`) and detect installed third-party icon packs to write exact `ComponentInfo{packageName/activityName}` entries into `appfilter.xml`.
- `android.permission.REQUEST_INSTALL_PACKAGES`: Required so IconCraft can hand the locally generated `IconCraft_Pack.apk` to Android's system Package Installer via `FileProvider`.

### On-Device Signing Security
On first build, IconCraft generates a unique RSA-2048 signing keypair and self-signed X.509 certificate stored in app-private storage (`filesDir/iconcraft_keystore.p12`), encrypted with a random 256-bit password sealed inside the hardware-backed **Android Keystore (`AES/GCM/NoPadding`)**. Every subsequent update to your icon pack is signed with this same persistent device key so Android updates `com.iconcraft.pack` seamlessly in place.

---

## 🛡️ Play Protect & Sideloading Note

Because IconCraft compiles and signs a brand-new `IconCraft_Pack.apk` locally on your phone using your device's unique self-signed certificate:
1. The first time you tap **Build & Install Icon Pack**, Android may ask you to grant **Install unknown apps** permission to IconCraft.
2. **Google Play Protect** may display an *"Unrecognized app / App scan recommended"* prompt when installing either IconCraft (if sideloaded from GitHub Releases) or the locally generated `IconCraft Pack` APK. This is standard Android behavior for self-signed APKs that have never been uploaded to Google Play—tap **Scan app** or **More details → Install anyway** to proceed.

---

## 🛠️ How to Build from Source

### Prerequisites
- **JDK 17**
- **Android SDK** (`compileSdk 35`, `minSdk 26`)

### 1. Build Debug APK & Run Unit Tests
The `:app` build automatically compiles `:iconpack_template` and copies the unsigned template APK into `app/src/main/assets/template_iconpack.apk` via the `:app:syncTemplateApk` Gradle task.

```bash
# Run unit tests (verifies binary AXML encoding, 500-slot template capacity, and v1+v2+v3 APK signing)
./gradlew test

# Assemble the debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug
```

### 2. Build Signed Release APK (Optional)
To build a signed, R8-minified release APK, create an untracked `keystore.properties` file in the repository root:

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

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
