# CloudGallery

An Android photo and video gallery inspired by Samsung Gallery (One UI 8). It shows your phone's
photos and your **OneDrive** photos in a single library.

[![Release](https://img.shields.io/github/v/release/Maxlego08/CloudGallery)](https://github.com/Maxlego08/CloudGallery/releases/latest)
![Android 16+](https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![MIT License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> A personal project, built and tested on Samsung Galaxy phones running One UI 8. It is not
> affiliated with Samsung or Microsoft.

---

## Features

### Library

- **Phone and OneDrive photos together** in one grid, sorted by date taken. Items that exist only
  on OneDrive show a cloud badge.
- **Large libraries**: smooth scrolling through tens of thousands of items, plus fast scrolling by
  date with a drag handle and a date bubble.
- **Pinch to zoom** the grid (2 to 9 columns), grouped by day or by month.
- **Duplicates merged**: a photo backed up to OneDrive and still on the phone appears once, after
  the app compares the file hashes.
- **Search** by file name, folder, album, date or type.

### Albums

- One album per phone folder, one album per OneDrive folder, and **custom albums** that never copy
  files.
- Sort, pin and rename albums, plus Favourites and Videos collections.
- **Hide albums**: each album can be shown, hidden in the Albums tab, or hidden in Photos too.
- **Open in OneDrive**: opens a OneDrive album's folder in OneDrive on the web.

### Viewer and videos

- **One UI-style transitions**: the photo grows out of its thumbnail and shrinks back into it when
  you close it. The curves were measured on Samsung Gallery.
- Pinch or double-tap to zoom, a thumbnail strip, and EXIF details (camera, settings, GPS
  location).
- Lossless rotation. Cropping saves a copy.
- **Video player** (Media3): ±10 s, speed, sound, resume where you left off, and streaming for
  OneDrive videos.

### OneDrive

- Sign in with a **personal Microsoft account**, **read-only**: the app never creates, changes or
  deletes anything on OneDrive.
- Previews sized to the network, and the original downloaded at deep zoom.
- Share and "Open with" for OneDrive items.
- **Offline copies** ("Keep offline") and **Cache all of OneDrive** (thumbnails and previews) for
  browsing without a connection.
- **Storage page**: space used, cache limits and cache clearing.
- **Space saver**: offers to move phone photos that already have an identical copy on OneDrive to
  the trash.

### Also included

- Selection mode with drag-to-select, and selection of a whole day or month. Available actions:
  favourite, share, add to album, copy and delete.
- **Move to Secure Folder** (Samsung).
- A loading animation on thumbnails.
- Light, dark and system themes. The interface is in English and French.
- Can be the default app for opening photos and videos.

### File safety

Every deletion goes through the **Android trash** (recoverable for 30 days) or the system's own
confirmation dialog. Indexing only reads the phone's files.

---

## Installation

**Requires Android 16 (One UI 8) or later.**

1. Download `CloudGallery-v*.apk` from the
   [latest release](https://github.com/Maxlego08/CloudGallery/releases/latest).
2. Open the file on your phone. If Android asks, allow installing apps from your browser or file
   manager.
3. On first launch, grant access to photos and videos.
4. To turn on OneDrive: *Menu > OneDrive > Sign in to OneDrive*.

With adb: `adb install -r CloudGallery-v1.0.0.apk`.

To make CloudGallery the default for opening photos, open a photo from another app (My Files, a
messaging app…), choose **CloudGallery**, then **Always**.

> Published APKs are signed with the project's release key. An APK you build yourself has a
> different signature: you have to uninstall one before installing the other, which erases your
> favourites and custom albums.

---

## Building from source

Requirements: a recent Android Studio (AGP 9.4), JDK 17 or later, and Android SDK API 37.

```bash
git clone https://github.com/Maxlego08/CloudGallery.git
cd CloudGallery
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.

### Enabling OneDrive in your build

Without any configuration, the app builds normally and the OneDrive features are hidden. To turn
them on, you need a free app registration in Microsoft Entra, with no client secret:

1. Register the app in the [Entra admin center](https://entra.microsoft.com):
   - account type: *Personal Microsoft accounts*;
   - Android platform: package `fr.eva.cloudgallery` and your signing key's hash;
   - delegated permissions: `Files.Read`, `User.Read` and `offline_access`.
2. Add to `local.properties` (not committed):

   ```properties
   msal.clientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   msal.signatureHash=XXXXXXXXXXXXXXXXXXXXXXXXXXX=
   ```

The full procedure, including how to compute the hash on Windows, is in
[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md#microsoft-entra-configuration-and-msal-setup).

### Signed release build

To sign `assembleRelease` with your own key, set these properties in
`~/.gradle/gradle.properties`:

```properties
cloudgallery.release.storeFile=C:/path/to/release.jks
cloudgallery.release.storePassword=…
cloudgallery.release.keyAlias=…
cloudgallery.release.keyPassword=…
msal.releaseSignatureHash=…   # this key's hash, also registered in Entra
```

Without these properties, the release build is signed with the debug key.

### Tests

```bash
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (device required)
```

---

## Tech stack

Kotlin, Jetpack Compose, Hilt, Room (with Paging 3), WorkManager, DataStore, Coil 3,
Media3 ExoPlayer, MSAL and Microsoft Graph (delta query).

Architecture, sync, cache, permissions and tests are documented in detail in
[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md).

## Known limitations

- OneDrive: personal Microsoft accounts only, one account at a time, read-only.
- No background sync: the library updates while the app is open.
- Favourites and custom albums belong to the app. They are not shared with Samsung Gallery and
  are lost if you uninstall it.
- No tiled decoding for very large images (above about 24 MP), and no animated GIFs or Motion
  Photos.
- Search does not recognise places or image content.

The full list is in [docs/DOCUMENTATION.md](docs/DOCUMENTATION.md#known-limitations).

## License

[MIT](LICENSE)
