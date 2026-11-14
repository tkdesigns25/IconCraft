# Save the text below as \*\*`PROJECT\_SPEC.md`\*\* inside a fresh empty project folder, open that folder in \*\*Antigravity\*\*, and tell it:  

# > \*"Read `PROJECT\_SPEC.md` carefully. Start by implementing \*\*Phase 1\*\*, verify it compiles cleanly, and check with me before moving to Phase 2."\*

# 

# Building it phase-by-phase like this keeps the AI from getting overwhelmed and makes debugging on your phone super easy.

# 

# ```markdown

# \# Project Specification: IconCraft (Custom Selective Icon Studio)

# 

# \## 1. Overview

# \*\*IconCraft\*\* is a 100% offline, zero-AI-API Android application built in \*\*Kotlin + Jetpack Compose\*\* that lets users replace the icon of \*\*only specific chosen apps\*\* (for example, \*Letterboxd\*) with custom uploaded images—while leaving all other installed apps completely untouched.

# 

# It also includes:

# 1\. A \*\*1x1 Home Screen Widget fallback\*\* for stock launchers that do not support third-party Icon Packs (e.g., Google Pixel, Motorola).

# 2\. An \*\*Experimental Lab\*\* feature that procedurally generates a matching full-device icon pack from a single inspiration image using pure Bitmap/Canvas math and Android's `Palette` API (no external AI image-generation APIs).

# 

# \---

# 

# \## 2. Tech Stack \& Constraints

# \- \*\*Language:\*\* Kotlin

# \- \*\*UI:\*\* Jetpack Compose + Material 3 (clean, modern, dark-mode-first aesthetic)

# \- \*\*Min SDK:\*\* 26 (Android 8.0) — required for `AdaptiveIconDrawable` inspection

# \- \*\*Target SDK:\*\* 35

# \- \*\*Networking / AI APIs:\*\* None (100% on-device processing)

# \- \*\*Key Libraries:\*\*

# &#x20; - `androidx.palette:palette-ktx` (color extraction for Experimental mode)

# &#x20; - `com.android.tools.build:apksig` (official APK v1/v2/v3 signing on-device)

# &#x20; - `io.coil-kt:coil-compose` (image loading)

# &#x20; - Lightweight pure-Java/Kotlin Android Binary XML (AXML) encoder or minimal AXML patcher for `res/xml/appfilter.xml` (see Section 5)

# 

# \### Required Permissions (`:app`)

# \- `android.permission.QUERY\_ALL\_PACKAGES` (to discover installed launcher apps like Letterboxd)

# \- `android.permission.REQUEST\_INSTALL\_PACKAGES` (to prompt installation of locally generated Icon Pack APKs)

# \- `FileProvider` configured in `AndroidManifest.xml` to share the generated `.apk` securely with Android's package installer.

# \- No broad storage permissions required—use Android's modern Photo Picker (`ActivityResultContracts.PickVisualMedia`).

# 

# \---

# 

# \## 3. Repository \& Module Architecture

# Use a \*\*2-module Gradle setup\*\*:

# 

# ```text

# IconCraft/

# ├── app/                        # Main user-facing app

# │   └── src/main/assets/

# │       └── template\_iconpack.apk   # Auto-copied from :iconpack\_template build

# └── iconpack\_template/          # Minimal blank Android Icon Pack APK project

# ```

# 

# \### Gradle Automation

# Configure a Gradle task in `:app` (e.g., `preBuild` dependency or `syncTemplateApk`) that builds `:iconpack\_template:assembleRelease` (or debug) and copies the resulting `.apk` directly into `app/src/main/assets/template\_iconpack.apk`.

# 

# \---

# 

# \## 4. Module 1: `:iconpack\_template` Specification

# This module compiles into a valid, empty Icon Pack APK that stock launchers recognize.

# 

# 1\. \*\*`AndroidManifest.xml` Intent Filters:\*\*

# &#x20;  Include standard launcher icon-pack discovery intent filters on the main activity/component so OnePlus (OxygenOS), Oppo (ColorOS), Realme UI, Nothing OS, Samsung Theme Park, and third-party launchers (Nova, Lawnchair, Smart Launcher) detect it:

# &#x20;  - `org.adw.launcher.THEMES`

# &#x20;  - `com.novalauncher.THEME`

# &#x20;  - `com.anddoes.launcher.THEME`

# &#x20;  - `com.gau.go.launcherex.theme`

# &#x20;  - `com.dlto.atom.launcher.THEME`

# &#x20;  - `android.intent.action.MAIN` + launcher theme categories (`com.anddoes.launcher.THEME`, `com.teslacoilsw.launcher.THEME`)

# 

# 2\. \*\*Pre-Allocated Drawable Slots (`res/drawable-nodpi/`):\*\*

# &#x20;  - Pre-generate \*\*200 tiny 1x1 transparent placeholder PNGs\*\* named `custom\_icon\_001.png` through `custom\_icon\_200.png` inside `iconpack\_template/src/main/res/drawable-nodpi/`.

# &#x20;  - Include a default pack icon (`ic\_launcher`) and label (e.g., `"IconCraft Pack"`).

# 

# 3\. \*\*Default Mapping Files:\*\*

# &#x20;  - Include `assets/appfilter.xml`, `res/raw/appfilter.xml`, and `res/xml/appfilter.xml`.

# &#x20;  - Include `assets/drawable.xml`, `res/raw/drawable.xml`, and `res/xml/drawable.xml`.

# 

# \---

# 

# \## 5. Crucial Technical Rules for APK Patching (Do Not Violate)

# 

# 1\. \*\*Leave Other Apps Untouched (Selective Mode):\*\*

# &#x20;  - The generated `appfilter.xml` must \*\*ONLY\*\* contain `<item>` entries for apps the user explicitly customized.

# &#x20;  - \*\*DO NOT\*\* include `<iconback>`, `<iconmask>`, `<iconupon>`, or `<scale>` tags in `appfilter.xml`. Omitting these ensures every unlisted app keeps its native system icon untouched.

# &#x20;  - Format for each entry:

# &#x20;    ```xml

# &#x20;    <?xml version="1.0" encoding="utf-8"?>

# &#x20;    <resources>

# &#x20;        <item component="ComponentInfo{com.letterboxd.buzz/com.letterboxd.buzz.ui.MainActivity}" drawable="custom\_icon\_001" />

# &#x20;    </resources>

# &#x20;    ```

# &#x20;    \*(Use the exact `packageName` and `className` returned by `PackageManager.queryIntentActivities`.)\*

# 

# 2\. \*\*`drawable.xml` Cleanliness:\*\*

# &#x20;  - Only list the actively used drawable names (`<item drawable="custom\_icon\_001" />`) in `drawable.xml` so manual icon pickers don't display 199 blank slots.

# 

# 3\. \*\*Launcher Compatibility (`res/xml/` vs `res/raw/` vs `assets/`):\*\*

# &#x20;  - Some launchers read plain-text XML from `assets/appfilter.xml` or `res/raw/appfilter.xml`.

# &#x20;  - Stricter stock launchers (and Samsung Theme Park) often require \*\*compiled Android Binary XML (AXML)\*\* inside `res/xml/appfilter.xml` and `res/xml/drawable.xml`.

# &#x20;  - Write plain-text UTF-8 XML to `assets/` and `res/raw/`, \*\*AND\*\* encode valid AXML into `res/xml/appfilter.xml` and `res/xml/drawable.xml` (using a small, self-contained AXML encoder in Kotlin).

# 

# 4\. \*\*APK Signing \& Re-Installation:\*\*

# &#x20;  - On first launch of IconCraft, generate a persistent RSA-2048 keypair and self-signed X.509 certificate stored in `context.filesDir` as a PKCS#12 (`.p12`) keystore.

# &#x20;  - Reusing the \*\*same local keystore\*\* every time lets the user update their installed `"IconCraft Pack"` whenever they add or edit icons without uninstalling first.

# &#x20;  - Sign the output `.apk` using Google's `ApkSigner` (`com.android.tools.build:apksig`) with v1 + v2 signing enabled.

# &#x20;  - Also increment the `versionCode` in the template `AndroidManifest.xml` AXML (or ensure `versionCode` increases per build) so Android allows seamless updates over the previous pack.

# 

# \---

# 

# \## 6. Core Features (`:app`)

# 

# \### Screen 1: Home / Custom Queue (Selective Mode — Default Tab)

# \- Clean list/grid showing the apps the user has customized so far (initially empty with a prominent \*\*"+ Customize an App"\*\* CTA, e.g., \*"Pick Letterboxd or any app"\*).

# \- Each queue item shows: Target App Name, Original Icon -> Arrow -> Custom Preview Icon, Edit button, Remove button.

# \- Bottom floating action button / sticky bar: \*\*"Build \& Install Icon Pack (X apps)"\*\*.

# 

# \### Screen 2: App Picker Dialog / Screen

# \- Queries all launchable apps using `PackageManager.queryIntentActivities(Intent(ACTION\_MAIN).addCategory(CATEGORY\_LAUNCHER), 0)`.

# \- Search bar at the top (real-time filtering by app name or package name).

# \- Displays app icon, app name, and `ComponentInfo` subtitle.

# 

# \### Screen 3: Single-App Icon Studio (Crop \& Polish)

# Triggered after picking an app (e.g., Letterboxd) and choosing an image via `PickVisualMedia`:

# \- Interactive pan/zoom square cropper.

# \- Optional simple visual controls (pure Bitmap/Canvas):

# &#x20; - \*\*Shape Mask:\*\* Square, Rounded Square (squircle-like), Circle, or Full Bleed.

# &#x20; - \*\*Padding / Scale Slider:\*\* Shrinks or expands the artwork inside a `512x512` canvas (helps icons match stock launcher sizing).

# &#x20; - \*\*Background Fill Color:\*\* Shown behind transparent PNGs or padded images (with quick eyedropper from the uploaded image).

# \- Saves output as a `512x512` `ARGB\_8888` PNG.

# 

# \### Screen 4: Build Progress \& Brand-Specific Apply Guide

# After signing the APK and triggering Android's package installer (`Intent.ACTION\_VIEW` with `application/vnd.android.package-archive` via `FileProvider`):

# \- Detect `Build.MANUFACTURER.lowercase()` and display tailored 2-step instructions:

# &#x20; - \*\*OnePlus / Oppo / Realme:\*\* \*"Long-press your Home Screen → Icons → Select 'IconCraft Pack' (or long-press just Letterboxd → Edit → tap the icon → choose from IconCraft Pack)."\*

# &#x20; - \*\*Nothing:\*\* \*"Long-press your Home Screen → Customization → Icon pack → Select 'IconCraft Pack'."\*

# &#x20; - \*\*Samsung:\*\* \*"Open Good Lock → Theme Park → Icon → Create New → Iconpack → Select 'IconCraft Pack'."\*

# &#x20; - \*\*Pixel / Motorola / Other:\*\* \*"Your stock launcher doesn't support third-party icon packs natively. Use the 'Add as 1x1 Home Widget' button below instead!"\*

# 

# \### Feature: Universal 1x1 Home Screen Widget Fallback

# \- Implement an `AppWidgetProvider` (`CustomIconWidgetProvider`) supporting resizable `1x1` widgets.

# \- Users can place a widget on their Home Screen, select any customized app from their queue, and tapping that widget launches the target app directly—with zero Android shortcut corner badge/watermark.

# \- Store widget-id-to-`(componentName, pngFilePath)` mappings in `SharedPreferences` / DataStore.

# 

# \---

# 

# \## 7. Experimental Lab Tab: "Auto-Theme Pack from 1 Image" (Zero AI)

# Include a second tab/drawer item clearly badged \*\*`EXPERIMENTAL`\*\* with a short note explaining it runs locally without AI.

# 

# \### Workflow:

# 1\. User uploads \*\*1 inspiration image\*\* (e.g., a movie poster or wallpaper).

# 2\. Extract colors using `androidx.palette:palette-ktx`:

# &#x20;  - `dominantColor`, `vibrantColor`, `darkMutedColor`, `lightVibrantColor`.

# 3\. User chooses \*\*1 of 3 Procedural Styles\*\* (all pure Kotlin `Bitmap`/`Canvas`/`ColorMatrix` operations):

# &#x20;  - \*\*Style A — Poster Texture:\*\* Uses the user's cropped photo as the background layer + converts each installed app's foreground drawable into a clean monochrome (white or dark) silhouette layered at \~65% size on top.

# &#x20;  - \*\*Style B — Neon Glow:\*\* Dark background (`darkMutedColor` or `#0D0D0D`) + glowing colored outline/tint of each app's foreground using `vibrantColor` and `BlurMaskFilter`.

# &#x20;  - \*\*Style C — Minimal Duotone:\*\* Flat solid background (`dominantColor` or `darkMutedColor`) + crisp contrasting tinted icon foreground.

# 4\. \*\*Foreground Extraction Logic:\*\*

# &#x20;  - If the target app's icon is an `AdaptiveIconDrawable`, extract `adaptiveIcon.foreground` cleanly (giving crisp logos without their original background boxes!).

# &#x20;  - If it's a legacy `BitmapDrawable`, apply luminance/saturation stripping + scale down cleanly.

# 5\. \*\*Live 4x4 Preview:\*\* Show an instant preview grid of 12–16 real installed apps (Letterboxd, YouTube, Spotify, etc.) before building.

# 6\. \*\*Batch Export:\*\* Map up to \*\*200 installed apps\*\* into `custom\_icon\_001`..`custom\_icon\_200` and feed them into the exact same APK Packager \& Signer pipeline.

# 

# \---

# 

# \## 8. Phased Build Plan for Antigravity

# Execute strictly in phases. Compile and verify each phase before proceeding to the next.

# 

# \- \*\*Phase 1 — Project Scaffold \& Template APK:\*\*

# &#x20; - Set up `:app` and `:iconpack\_template` Gradle modules, permissions, `FileProvider`, and the Gradle task that copies `template\_iconpack.apk` into `:app/src/main/assets/`.

# &#x20; - Implement the Searchable App Picker screen (`PackageManager` query returning `ComponentInfo`).

# \- \*\*Phase 2 — Single-App Icon Studio + APK Patcher \& Signer:\*\*

# &#x20; - Implement Photo Picker + `512x512` Bitmap cropper/editor + Selective Queue UI.

# &#x20; - Implement Zip patching (`assets/`, `res/raw/`, and binary AXML in `res/xml/`), persistent `.p12` keystore generation, `apksig` signing, and APK install prompt.

# &#x20; - \*\*Milestone Test:\*\* Customize \*only\* Letterboxd, install the generated APK, and verify only Letterboxd changes.

# \- \*\*Phase 3 — Brand Apply Guide \& 1x1 Widget Fallback:\*\*

# &#x20; - Add manufacturer detection + tailored instructions card.

# &#x20; - Implement `CustomIconWidgetProvider` (`1x1` launcher widget without watermarks).

# \- \*\*Phase 4 — Experimental Procedural Theme Generator:\*\*

# &#x20; - Add the `Experimental` tab with `Palette` extraction, the 3 procedural Canvas styles (Poster Texture, Neon Glow, Minimal Duotone), live preview grid, and batch 200-slot APK export.

# ```

# 

# \### One quick tip while working in Antigravity:

# When you reach \*\*Phase 2\*\* and test the first generated APK on your phone, tell Antigravity what phone brand you're testing on (e.g., Samsung, OnePlus, Nothing, Pixel) so it can tune the `AndroidManifest.xml` intent filters or AXML output immediately if your phone's launcher is picky!

