# CloudGallery

Android photo/video gallery for personal use. It follows the ergonomics of Samsung Gallery
(One UI 8) and is meant, eventually, to bring the device's media and OneDrive's media together in a
single library.

> Detailed technical documentation (architecture, sync, cache, tests). For a quick overview and
> installation, see the [README](../README.md).

- Package: `fr.eva.cloudgallery`, version `1.0.0` (versionCode 4)
- `minSdk 36` (Android 16), `targetSdk 37`, `compileSdk 37`
- Target device: Samsung Galaxy S22 on One UI 8 / Android 16, library of 10,000 to 100,000 media items

---

## Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Dependencies](#dependencies)
4. [Android permissions](#android-permissions)
5. [Local indexing](#local-indexing)
6. [OneDrive sync and cache management](#onedrive-sync-and-cache-management)
7. [Microsoft Entra configuration and MSAL setup (phase 3)](#microsoft-entra-configuration-and-msal-setup)
8. [Building](#building)
9. [Installing on a Samsung Galaxy S22](#installing-on-a-samsung-galaxy-s22)
10. [Tests](#tests)
11. [Known limitations](#known-limitations)

---

## Overview

Available features (phases 1 and 2 for local media, phase 3 for OneDrive):

- **Photos / Albums / Menu tabs**, with a tab bar inspired by One UI.
- Paged **Photos grid**, sorted by capture date (`dateTaken DESC, id DESC`). Five pinch-zoom
  levels (2, 3, 4, 6 or 9 columns in portrait, ×1.75 in landscape), grouped by day
  (levels 0 to 2) or by month (levels 3 and 4). The chosen level is remembered.
- **Fast scroll**: a handle on the right of the grids (Photos, albums, favorites, videos, search),
  visible while scrolling, with a date bubble while dragging. Its position spans the whole
  list (dates of all media read from the database); a date outside the loaded pages reloads
  pagination from its first media item (`Pager(initialKey = rank)`).
- **Selection mode**: long press, drag to extend the selection (including onto items not yet
  loaded), select a whole day or month. Actions: favorite, share, add to an album, copy to a
  folder, delete.
- **Albums**: one album per local folder (MediaStore bucket), plus custom albums
  ("virtual" albums, which hold references rather than copies). Custom sort, by name, by
  date or by number of items. An album can be pinned and its display name changed (the folder
  on disk is not renamed). The Favorites and Videos collections are also offered.
- **Viewer**: horizontal paging, pinch or double-tap zoom, thumbnail strip
  (filmstrip) and EXIF details panel (camera, ISO, flash, GPS with a link to a maps
  app, location). Lossless rotation (JPEG/WebP, through the EXIF orientation). Cropping
  saves a **copy** and never modifies the original.
- **Video player** (Media3 ExoPlayer) with custom controls: ±10 s, speed, mute/unmute,
  screen rotation, resume at the last position (`playback_position` table) and
  "Open with another app".
- **Opening from other apps**: `VIEW` intent for `image/*` and `video/*`, `APP_GALLERY`
  category. A file that is not in the index (FileProvider, partial access…) is shown
  read-only.
- **OneDrive (read-only)**: "OneDrive account" screen (Menu and Settings) to sign in
  with a personal Microsoft account, see the profile and quota, sync and sign out.
  Each OneDrive folder that contains photos or videos becomes a **OneDrive album** (cloud badge).
  Cells show Microsoft Graph thumbnails, with a cloud badge for an item stored
  only on OneDrive; the viewer shows a large preview of it. **"Show OneDrive items in Photos"**
  setting (on by default, see
  [OneDrive sync](#onedrive-sync-and-cache-management)). No action
  modifies OneDrive.
- **Settings**: OneDrive account and its sync status (if this build is configured for
  OneDrive), theme (system / light / dark), video duration display, autoplay,
  Android trash, confirmation before deleting, indexing status, full
  re-index.
- UI in **English** (default) and **French** (`values-fr`).

**File safety.** The app never deletes or modifies any file without a system
confirmation. Deletion always goes through `MediaStore.createTrashRequest` (Android trash,
recoverable for 30 days, default option) or `MediaStore.createDeleteRequest`, and Android then shows
its own confirmation dialog. Rotation requests write permission with
`MediaStore.createWriteRequest`. The indexer only modifies the Room database, never the files.
OneDrive is accessed read-only (`Files.Read` scope): no OneDrive file is created, modified
or deleted, and an item stored only on OneDrive is never offered for sharing, copying or
deletion.

---

## Architecture

### Layers

```
fr.eva.cloudgallery
├── ui/          Compose + ViewModels (Hilt), type-safe navigation (kotlinx.serialization)
│   ├── main/        tabbed screen, MediaSyncCoordinator (local indexing and OneDrive sync)
│   ├── account/     "OneDrive account" screen (sign-in, profile, sync, sign-out)
│   ├── photos/      Photos tab             ├── albums/   Albums tab, album grids
│   ├── grid/        paged grid, zoom, selection, day/month separators
│   ├── viewer/      viewer, zoom, EXIF, cropping, filmstrip, external image
│   ├── video/       Media3 player and its controls
│   ├── menu/ settings/ permissions/ navigation/ components/ (One UI components) theme/ image/ util/
├── domain/      models (Media, Album, MediaContext, Settings, Cloud) + repository interfaces
│   └── repository/Repositories.kt   MediaRepository, AlbumRepository, FavoritesRepository,
│                                    LocalMediaIndexer, LocalMediaWriter, SettingsRepository,
│                                    PlaybackPositionRepository, CloudAccountRepository,
│                                    CloudMediaSyncer, CloudTokenProvider
├── data/
│   ├── db/          Room: AppDatabase, entities, DAOs
│   ├── local/       MediaStoreIndexer (MediaStore -> Room), MediaStoreWriter (writes with
│   │                confirmation), MediaStoreLogic (pure logic, tested on the JVM)
│   ├── cloud/       OneDrive: CloudFeature (build configured or not), CloudSessionCoordinator
│   │   │            (session as seen by the UI), CloudDataPurger (removal of OneDrive data)
│   │   ├── auth/    MSAL: MsalClientProvider, MsalAuthService (account state, tokens), CloudAuthLogic
│   │   ├── graph/   Microsoft Graph: GraphHttpClient (OkHttp, 401/429/410), GraphApi, DTOs
│   │   ├── sync/    OneDriveSyncer (delta), CloudSyncWriter (Room), OneDriveLogic (pure logic)
│   │   └── thumb/   Graph thumbnails for Coil: CloudThumbnailFetcher/Keyer, CloudThumbnailCache
│   ├── repository/  repository implementations
│   ├── prefs/       SettingsRepositoryImpl (DataStore Preferences)
│   └── mapper/      entities -> domain models
└── di/          Hilt modules
```

The `ui` layer depends only on the `domain` interfaces. The `data` implementations are bound by
Hilt. Only `CloudGalleryApp` connects the `data` layer to Coil (registration of the OneDrive
thumbnail fetcher in the ImageLoader built by `ui/image`).

### Hilt modules (`di/`)

| Module | Role |
|---|---|
| `CoroutinesModule` | `@IoDispatcher` (`Dispatchers.IO`), `@DefaultDispatcher` and `@ApplicationScope` (`CoroutineScope(SupervisorJob() + Default)`) qualifiers for work that must outlive screens |
| `DatabaseModule` | `AppDatabase` (singleton, file `cloudgallery.db`, migrations 1→2 and 2→3) and the 10 DAOs |
| `DataStoreModule` | Single `DataStore<Preferences>` instance (file `settings`) |
| `RepositoryModule` | `@Binds` from the domain interfaces to `MediaRepositoryImpl`, `AlbumRepositoryImpl`, `FavoritesRepositoryImpl`, `MediaStoreIndexer`, `MediaStoreWriter`, `SettingsRepositoryImpl` and `PlaybackPositionRepositoryImpl` |
| `CloudModule` | `@Binds` for `CloudAccountRepository` (`CloudSessionCoordinator`), `CloudTokenProvider` (`MsalAuthService`), `CloudMediaSyncer` (`OneDriveSyncer`) and `CloudThumbnailCache` (`CoilCloudThumbnailCache`). Acyclic graph: the Graph client depends only on the token provider |
| `CloudNetworkModule` | Microsoft Graph `OkHttpClient`, injected through `dagger.Lazy` and created on first call, on the I/O dispatcher |

`CloudGalleryApp` (`@HiltAndroidApp`) also provides the single Coil `ImageLoader`
(`SingletonImageLoader.Factory`): memory cache of about 25% of the app's memory, disk
cache of 256 MB (OneDrive thumbnails only), MediaStore thumbnails loaded through
`ContentResolver.loadThumbnail` (`LocalThumbnailFetcher` fetcher), video frames extracted by
`VideoFrameDecoder`, OneDrive thumbnails loaded by `CloudThumbnailFetcher` (see
[Thumbnails and preview](#thumbnails-and-preview-phase-3)). The OneDrive thumbnail factory is
lightweight: the Graph client, OkHttp and MSAL are only created on the first download, off the main
thread.

### Data flow

```
MediaStore (images + videos, VOLUME_EXTERNAL)
   │  MediaStoreIndexer: queries in batches of 500, on Dispatchers.IO
   ▼
Room (media, media_source, album, …)        <- source of truth for the UI
   │  DAO -> Room PagingSource (room-paging)
   ▼
Pager (pageSize 150, initialLoad 450, prefetch 450, maxSize 1800, no placeholders)
   │  Flow<PagingData<MediaSummary>>, cachedIn(viewModelScope)
   ▼
Compose (LazyVerticalGrid + collectAsLazyPagingItems, Coil AsyncImage)
```

OneDrive follows the same principle: `OneDriveSyncer` reads Microsoft Graph (delta) and
`CloudSyncWriter` writes to Room (one transaction per page); the UI never queries Graph, except
for the thumbnails loaded by Coil.

The UI never reads MediaStore directly. It observes Room, which automatically invalidates the
`PagingSource`s on every indexer write. `maxSize = 1800` bounds memory after a long
scroll through 100,000 media items. Placeholders are disabled because the day/month headers
are inserted between the loaded items.

### Room schema (version 4, schemas exported to `app/schemas/`)

A **logical media item** (`media`) can have **several sources** (`media_source`): the local
MediaStore file (`LOCAL`), the OneDrive item (`ONEDRIVE`) and an offline copy (`OFFLINE_COPY`,
phase 4: `externalId` = OneDrive item id, no folder or URI, file in `cached_file`).
Favorites, albums and playback positions point to the logical media item, which lets them
survive the disappearance or replacement of one of the sources. The `hasLocal`,
`hasCloud`, `hasOffline`, `primaryUri`, `cloudItemId` and `cloudCTag` columns of `media` are denormalized
from the **visible** sources in `media_source` (`MediaDao.refreshSourceFlags`), which avoids
any join in the grid. `primaryUri` is only set by a local file that is present: a non-null URI
means "usable local file". In phase 3, a OneDrive item is always its
own logical media item (no merge with a local file, see the limitations).

Migrations: 1→2 (phase 2), 2→3 (phase 3: `media.cloudItemId` / `media.cloudCTag` columns,
`cloud_folder` table), 3→4 (phase 4: `cached_file.version`, `download_task.cloudItemId` / `cTag` /
`attempts`) and 4→5 (phase 5: `cloud_folder.deletedAt`), tested by `MigrationTest` (2→3, 1→3,
3→4, 2→4, 1→4, 4→5, 3→5, 2→5, 1→5).

| Table | Role |
|---|---|
| `media` | Logical media item: type, name, MIME, `dateTaken` (EXIF/MediaStore, falling back to the date added, then the modification date; for OneDrive `photo.takenDateTime`, then `fileSystemInfo.createdDateTime`, then `createdDateTime`), dimensions, duration, size, orientation, `primaryUri`, source flags, `cloudItemId` / `cloudCTag` (OneDrive thumbnails), `contentHash` (`qxh:<quickXorHash>`: the local file once its hash is computed, otherwise the OneDrive item). Indexes `(dateTaken, id)`, `(mediaType, dateTaken)`, `displayName`, `contentHash` |
| `media_source` | One physical source of a media item. Unique `kind` + `externalId` (`image:123` / `video:45` for MediaStore, because image and video `_ID`s can overlap; item id for OneDrive), `uri`, `bucketKey` (`local:<BUCKET_ID>` or `onedrive:<parent folder id>`), relative path (`OneDrive/Photos/2024/` for OneDrive), `generation` (GENERATION_MODIFIED), `eTag`/`cTag`/`sha1Hash` (OneDrive), `quickXorHash` (from Graph for OneDrive, computed by the app for a local file), `hashSignature` (local file: size, modification date and generation when the hash was computed), `volumeName`, `missingSince` (source hidden but kept) |
| `album` | Folder album (`LOCAL_FOLDER` or `CLOUD_FOLDER`, `sourceKey` = bucketKey) or virtual album (`sourceKey` null). Custom name, pin, order, cover, hiding. A customized folder is never deleted automatically (except on OneDrive sign-out, after confirmation) |
| `cloud_folder` | Known OneDrive folders (id, parent, name, root): names and paths of OneDrive albums, hiding the contents of a deleted folder. `deletedAt`: deletion marker, kept as long as the folder's subtree contains hidden items (restore from the recycle bin); never shown |
| `album_media` | Membership of a media item in a virtual album (references, no copy) |
| `favorite` | App-specific favorites (`mediaId`, date added) |
| `cached_file` | OneDrive files copied to the device, one per media item and per level (`ORIGINAL`: temporary originals; `OFFLINE`: offline copies, `pinned`), path relative to the level's folder, size, `lastAccessAt` (cleanup), `version` = cTag of the copied content (null = never considered up to date), `pinned` = kept offline (never evicted). Replacing a copy: `CachedFileDao.replace` (same row) |
| `download_task` | Offline copy queue (`QUEUED`, `RUNNING`, `PAUSED` = stopped by the user, `FAILED`; a successful task is deleted), bytes received/total, `keepOffline`, `cloudItemId` / `cTag` (version of the partial file), `attempts`, `error` (`DownloadError` code, never a URL). Only one open task per media item (`DownloadTaskDao.enqueueIfAbsent`, no partial index) |
| `sync_cursor` | Cursor per provider: `mediastore` (generation + `MediaStore.getVersion`) and `onedrive` (Graph deltaLink, `version` = `<drive.id>\|<$select version>`). Dates of the last sync and of the last full sync, last error (code, translated at display time) |
| `user_settings` | Reserved key/value table. Current settings are stored in DataStore |
| `playback_position` | Video resume position (saved only between 3 s after the start and 3 s before the end) |

Foreign keys to `media` use `ON DELETE CASCADE`. The database is **excluded from Android
backups** (`backup_rules.xml`, `data_extraction_rules.xml`): it references MediaStore `_ID`s
specific to the device. The MSAL token files (SharedPreferences encrypted with a Keystore
key, unusable on another device), the MSAL key wrapped by the Keystore
(`app_fr.eva.cloudgallery/adalks`, a directory used only by MSAL), the OneDrive sync
state (`cloud_sync.xml`), `msal_config.json` (`noBackupFilesDir`) and the offline
copies (`files/offline/`) are excluded as well. Only the app's preferences are
backed up.

---

## Dependencies

Exact versions taken from `gradle/libs.versions.toml`:

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle (wrapper) | 9.6.0 |
| Kotlin (Compose and Serialization plugins) | 2.4.21 |
| KSP | 2.3.12 |
| Hilt (`hilt-android`, `hilt-compiler`) | 2.60.1 |
| AndroidX Hilt (`hilt-navigation-compose`, `hilt-work`, `hilt-compiler`) | 1.4.0 |
| Core KTX | 1.19.1 |
| Activity Compose | 1.13.0 |
| Lifecycle (runtime, runtime-compose, viewmodel-compose, process) | 2.11.0 |
| Navigation Compose | 2.10.2 |
| Compose BOM | 2026.09.00 |
| Material Icons Extended | 1.7.8 |
| Room (runtime, ktx, paging, compiler, testing) | 2.8.5 |
| Paging (runtime, compose, testing) | 3.5.2 |
| WorkManager (`work-runtime-ktx`) | 2.12.0 (declared, not used yet) |
| DataStore Preferences | 1.2.1 |
| Media3 (exoplayer, ui-compose, ui) | 1.11.1 |
| ExifInterface | 1.4.2 |
| Coil 3 (`coil-compose`, `coil-video`) | 3.6.3 |
| MSAL Android (`com.microsoft.identity.client:msal`) | 8.5.0 |
| OkHttp | 5.5.0 |
| kotlinx-coroutines (android, test) | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 |
| JUnit | 4.13.2 |
| AndroidX Test ext JUnit | 1.3.0 |
| Espresso Core | 3.7.0 |

The `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 plugin is declared in
`settings.gradle.kts`.

**Extra repository for MSAL.** MSAL depends (through `com.microsoft.identity:common`) on
`com.microsoft.device.display:display-mask:0.3.0`, published only on the Surface Duo SDK Maven
feed (`https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1`).
This repository is declared in `settings.gradle.kts` as `exclusiveContent`, restricted to the
`com.microsoft.device.display` group: it is never queried for anything else. MSAL's R8 rules
are shipped in its AARs (no rule to add on the app side).

OkHttp is used only for the Microsoft Graph client and OneDrive thumbnails (custom Coil fetcher):
Coil's network module (`coil-network-okhttp`) is not used.

---

## Android permissions

Declared in `AndroidManifest.xml`:

| Permission | Purpose |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Reading the whole library (full access) |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Partial access "Select photos and videos" (Android 14 and later) |
| `ACCESS_MEDIA_LOCATION` | Unredacted GPS coordinates in EXIF (`MediaStore.setRequireOriginal`) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Microsoft sign-in (MSAL) and Microsoft Graph (OneDrive). No network access without a signed-in OneDrive account |
| `RUN_USER_INITIATED_JOBS` | Downloading offline copies through a user-initiated data transfer job (JobScheduler `setUserInitiated`), with no quota or time limit |
| `<queries>` `com.samsung.knox.securefolder` | Knowing whether the Samsung Secure Folder app is installed, to offer "Move to Secure Folder" (no Knox API used) |
| `POST_NOTIFICATIONS` | Offline copy progress. Requested only once, on the first "Keep offline"; if denied, downloads still work (progress visible in the app and in the Task Manager) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | "Cache all of OneDrive": WorkManager work as a foreground service (`SystemForegroundService` declared as `dataSync`), with a progress notification and "Cancel" |

MSAL's libraries add to the merged manifest the `NFC` permission, optional hardware
features (NFC, USB host: YubiKey security keys) and a few technical
activities. CloudGallery does not use them; they do not prevent installation on a device
that lacks these features.

The four media permissions are requested together, and Android then offers "Allow all",
"Select photos and videos" or "Don't allow". The state is rechecked on every `ON_RESUME`
(`MediaPermissionController`).

Behavior depending on the state (`MediaAccess`):

- **FULL** (images and videos granted): full indexing. A source that disappears from MediaStore
  and whose absence is confirmed (including by querying the trash and pending
  items) is removed from the database, unless the disappearance is massive (see below).
- **PARTIAL** (user selection, or only one of the two types granted): the app
  shows only the accessible items, with an "Access limited to some photos · Manage" banner
  that relaunches the system request to change the selection. **Nothing is permanently removed from the
  database**: items that are no longer visible are only hidden (`missingSince`), with their
  favorites and albums, and they reappear if access is restored. Folder albums are not
  cleaned up.
- **NONE**: permission screen. After a permanent denial, it offers to open the app's
  settings. The indexer does not run: without permission, MediaStore would return an empty list,
  and the database is protected against being wiped.
- **ACCESS_MEDIA_LOCATION denied**: the app works normally. The details panel
  simply reads the image with its GPS coordinates redacted.

---

## Local indexing

Implemented in `data/local/MediaStoreIndexer.kt`, with the pure logic in `MediaStoreLogic.kt`.
It runs entirely on `Dispatchers.IO`, and a `Mutex` prevents two concurrent
syncs.

1. **Generation cursor.** Before any read, the indexer records `MediaStore.getGeneration` (the
   maximum across mounted external volumes). An incremental sync only rereads rows whose
   `GENERATION_MODIFIED` exceeds the cursor stored in `sync_cursor` (`provider = "mediastore"`).
   A change committed during the sync will have a higher generation and will be reread the next
   time. A full reread only rewrites what has changed.
2. **MediaStore version.** `MediaStore.getVersion(context, VOLUME_EXTERNAL)` is stored. If it
   changes (MediaStore database recreated, restore), generations and `_ID`s are no longer
   comparable. The indexer then performs a full reread, marks local sources "to be
   re-identified" and then re-associates them by fingerprint (`relative path | name | size`) in order to
   keep favorites, albums and playback positions.
3. **Deletion diff.** The visible `_ID`s are compared with the known sources. A missing source
   is:
   - **hidden** (`missingSince`) if access is partial, if it is still in the trash or
     pending (`MATCH_INCLUDE` query), if its volume is unmounted (SD card) or if the check
     is impossible;
   - **removed from the database** (never from disk) if it has really disappeared and access is
     full;
   - subject to the **mass-deletion brake**: if more than 200 sources **and** more than 25% of the
     library disappear at once, they are only hidden and a warning
     is shown. A hidden source is only purged after **30 days** and once its disappearance is
     confirmed;
   - if MediaStore returns nothing at all while the database contains media, nothing is
     touched (transient provider state).
4. **Folder albums**: created or updated from the buckets. A folder that disappears is only
   deleted with full access, and never if it carries a customization.
5. **ContentObserver** on `MediaStore.Files` (`VOLUME_EXTERNAL`, descendants included). The
   notifications are collected into a `CONFLATED` channel with a **1.5 s debounce**, then
   trigger an incremental sync. Requests close together are merged: a sync that
   starts after a request satisfies it.
6. **Foreground sync**: `CloudGalleryApp` observes `ProcessLifecycleOwner` and calls
   `MediaSyncCoordinator.onForeground()` every time the app returns to the foreground. The sync is also
   launched when access changes (granted, extended, new selection) and on demand from the Menu
   ("Sync now") or Settings ("Full re-index", after confirmation).

There is **no background sync** while the app is closed. Changes are
caught up the next time the app comes to the foreground, thanks to the generation cursor.

---

## OneDrive sync and cache management

> **Sync, thumbnails and preview: implemented (phase 3).** Video streaming, adaptive
> previews, image originals, sharing and "Open with": **implemented (phase 4)**. Offline
> and managed cache (LRU, Storage page): **planned (phase 4 slice 3, phase 5).** The feature only appears
> in a build configured for OneDrive (see
> [Microsoft Entra configuration](#microsoft-entra-configuration-and-msal-setup)).

### Sync (phase 3)

- **Authentication**: MSAL Android 8.5.0 as a public client (no secret), single-account mode,
  AAD authority with the `PersonalMicrosoftAccount` audience (personal Microsoft accounts). Requested
  scopes: `Files.Read` and `User.Read` (MSAL itself adds `openid`, `profile` and
  `offline_access`). Sign-in opens in a browser tab; tokens are renewed
  silently (`acquireTokenSilent`, on the I/O thread). If interaction is required,
  the Account screen shows "Sign-in required" and the data already indexed is kept. While
  offline, the session remains valid. A token requested before a sign-out and obtained afterwards is
  discarded (neither cached nor "signed in" account restored). A failed read of the MSAL account
  is retried automatically (at most every 30 s) before a sync or a thumbnail.
- **Inventory**: Microsoft Graph `GET /me/drive/root/delta` over **the whole OneDrive**, with an
  explicit `$select` (`id, name, size, eTag, cTag, createdDateTime, lastModifiedDateTime,
  fileSystemInfo, file, folder, package, image, photo, video, parentReference, deleted, root,
  remoteItem`). `@odata.nextLink` pages are followed up to the `@odata.deltaLink`, which is saved
  **at the end** in `sync_cursor` (`provider = "onedrive"`): an interrupted pass resumes from
  the previous cursor. Links are used as-is, never rebuilt. The cursor is tied
  to the drive (`drive.id`) and to the `$select` version: changing the field list forces a
  full enumeration.
- **Filtering**: only `image/*` and `video/*` files are kept (SVG excluded; when there is no
  MIME type, the `image`/`photo`/`video` facets decide). OneNote notebooks (`package`) and
  items shared by others (`remoteItem`) are ignored.
- **Writing**: one page = one Room transaction. Each OneDrive item becomes **its own logical
  media item** with a `media_source` of type `ONEDRIVE` (`externalId` = item id, `bucketKey` =
  `onedrive:<parent folder id>`, readable path `OneDrive/Photos/2024/`, `cTag`/`eTag`,
  `quickXorHash`, `sha1Hash` if provided). It is never linked to a local file by name;
  at the end of the pass, `MediaMerger` merges it with a phone file that has the **same QuickXorHash
  and same size** (see "Deduplication").
- **OneDrive albums**: each folder that contains photos or videos becomes a
  `CLOUD_FOLDER` album (cloud badge), whose name follows renames on OneDrive. Display renaming
  and pinning are possible; no deletion and no "Copy to" a OneDrive folder.
- **Deletions**: a `deleted` item is **hidden** (`missingSince`), then removed from the index
  after **30 days**. A deleted folder only hides its contents at the end of the pass, if it is
  still missing; its `cloud_folder` row is then marked deleted (`deletedAt`), along with its
  subfolders and intermediate folders, as long as its subtree contains hidden items
  (removed once those are purged). A marked folder that reappears (restored from the OneDrive
  recycle bin) with hidden items in its subtree triggers a full enumeration (its
  items are not necessarily returned one by one): the deltaLink is cleared in the page's
  transaction (even if interrupted, the next pass starts again from a full enumeration) and
  the enumeration starts right away. A new folder (without a marker) no longer triggers one, unless
  hidden items remain from a folder deleted before v5 (without a marker), until they are purged.
  `410 Gone` (expired delta token): new full enumeration from the `Location` URL
  (at most two per pass), then the items not seen again are hidden. Nothing is deleted
  immediately; no local or OneDrive file is touched.
- **Triggers**: after sign-in, when returning to the foreground (at most once every
  5 minutes, regardless of the media permission) and through "Sync now" (Menu, which
  also launches local indexing, and Account screen). "Full re-index" (Settings) stays
  local. Only one pass at a time; simultaneous requests are merged.
- **Throttling**: `429`, `502`, `503`, `504`: `Retry-After` (in seconds) is honored, otherwise
  exponential backoff 2 s → 60 s, at most 5 retries; a `Retry-After` longer than
  10 minutes suspends the sync until that deadline. Any requested pause ("pause all further
  requests") applies to all subsequent Graph requests, including when the request that
  received it gives up: a pause of up to 10 minutes is waited out, beyond that requests (thumbnails
  included) fail immediately without being sent. The end of the sync pause is
  saved (`cloud_sync.xml`) and honored after a restart; it is cleared along with the
  OneDrive data. `401`: one forced token renewal, then, if the new token is also
  rejected, sign-in required (the account switches to "Sign-in required"). The token is only
  sent to `https://graph.microsoft.com`.
- **Different account**: if the signed-in OneDrive is not the one the index data comes from, the sync is
  suspended and the Account screen offers to replace that data, **after confirmation**. Nothing is
  purged automatically. As long as this mismatch is detected, no thumbnail for the other
  account's items is requested from Graph (only those already in the disk cache are shown).
- **Sign-out** (confirmation required): MSAL sign-out, then removal of all OneDrive
  data from the index: sources, media stored only on OneDrive with their favorites and
  album additions, OneDrive albums (including renamed or pinned ones), folders, cursor,
  cached thumbnails and originals, **offline copies** and the download queue. Ongoing
  downloads are stopped before sign-out. The confirmation states the number of
  offline copies that will be deleted and, if any, how many are the **last copies** of items
  already deleted from OneDrive. The same applies to purging leftover data and to switching
  accounts. Files on OneDrive and on the phone are not modified. If the MSAL sign-out
  fails, the data is kept and downloads resume.
- **"Show OneDrive items in Photos" setting** (on by default, Settings and
  Account screen): when off, Photos and Videos (grid, counts, selection by day or by
  drag) only show media stored on the device or **kept offline** (always
  shown, even with the setting off); items
  stored only on OneDrive remain visible in their OneDrive albums, Favorites and
  custom albums. When on, everything is mixed: a photo stored on the phone **and** on
  OneDrive (camera backup, for example) appears **twice** until the
  local file's hash is computed (while charging), then only once (deduplication).
- **Status**: subtitle of the Menu's OneDrive entry (sync in progress, date of the last sync
  and number of items, or error), "OneDrive sync" row in Settings and Account
  screen (profile, quota, "Sync now"). Errors are stored as
  codes and translated at display time.
- **Logs**: no token, no deltaLink, no account name.
- **Scheduling** (phase 5, planned): periodic WorkManager (network constraint, possibly
  Wi-Fi only) in addition to the foreground sync.

### Thumbnails and preview (phase 3)

- **Coil**: `CloudThumbnail` model (item id + cTag), `CloudThumbnailKeyer` and
  `CloudThumbnailFetcher` (`data/cloud/thumb/`). Request
  `GET /me/drive/items/{id}/thumbnails/0/{size}/content`; Graph redirects to the image, and OkHttp
  follows the redirect without the `Authorization` header (different host).
- **Sizes**: square tile `c{n}x{n}_crop` at the cell's size step (192 to 1024 px, same
  steps as local thumbnails), `smallSquare` for 96 px. If Graph rejects a custom
  size (400 or 404), the standard size `mediumSquare` (176 px) or `largeSquare` (800 px)
  is requested, and the standard sizes are then used directly. Viewer
  preview: `c1600x1600` or `c2048x2048` step (original aspect ratio, see "Quality
  levels"), falling back to the lower step and then `large` (800 px). Graph thumbnails are already
  correctly oriented.
- **Cache**: key = id + cTag + step (`p<side>` for a preview; a rename does not invalidate it,
  new content does).
  Written to the ImageLoader disk cache (`cacheDir/coil`, 256 MB, managed by Coil): thumbnails
  already seen are shown offline. At most 4 simultaneous downloads (Coil
  cancels those of cells that scrolled off-screen); a thumbnail that is not found (404) is not
  requested again for 10 minutes. Downloads only for a signed-in account; sign-out
  clears the disk cache and the OneDrive thumbnails from the memory cache. A thumbnail
  being downloaded during a purge is not saved (`CloudDataGeneration`,
  incremented before the purge and shared by thumbnails and originals).
- **Local priority**: a cell, an album cover, the thumbnail strip or the album
  picker always use the local file if it exists (MediaStore), otherwise the OneDrive thumbnail.
- **Viewer**: an image stored only on OneDrive shows the preview, zoomable, with the
  caption "OneDrive preview: zoom in to see the original"; the original replaces the preview on
  deep zoom (see "Image originals"). A video shows the preview, a play button
  (streaming) and its duration. Share and "Open with" download the original; rotation, cropping,
  "Copy to" and deletion remain reserved for files on the device (hidden). Preview
  unavailable (offline without cache, expired session): explanatory message.
- **Selection**: Delete and Copy are disabled if the selection contains no local
  file (a whole OneDrive album, for example); for a mixed selection, they only process the
  local files, and the confirmation dialogs show the exact number of affected files and
  the number of unaffected OneDrive items. Share also accepts OneDrive items (see
  "Sharing and Open with"). Favorite and Add to album apply to everything.
- **Video player**: a local file is played first; otherwise, for a signed-in account, the OneDrive
  video is streamed (see "Quality levels"). In the player, "Open with"
  is only offered for a local file (from the viewer, it downloads the OneDrive video).

### Quality levels (phase 4)

| Level | Source | Use | Status |
|---|---|---|---|
| Thumbnail | `/items/{id}/thumbnails/0/c{n}x{n}_crop/content` (fallback `smallSquare`/`mediumSquare`/`largeSquare`) | Grid, album covers, thumbnail strip | Implemented (phase 3) |
| Preview | `/items/{id}/thumbnails/0/c{1600\|2048}x{…}/content` (fallback to the lower step, then `large`) | Viewer (zoomable) | Implemented (phase 3, steps in phase 4) |
| Original | `@microsoft.graph.downloadUrl` (temporary URL, no authentication header, HTTP Range compatible) | Video streaming | Implemented (phase 4) |
| Original | same, temporary file `cacheDir/onedrive/originals/` | Deep zoom, sharing, "Open with" | Implemented (phase 4) |

**Preview step** (`previewSideFor`): the smallest step (1600 or 2048 px) that covers the long
side of the screen, never beyond what the image itself requires, and at most 1600 on a metered
network (mobile data, Data Saver, unknown state). The step is fixed when the page
opens. If Graph rejects `c2048x2048` (400/404) while `c1600x1600` works, the 2048 step is
dropped until restart. 2048 previews take up more of the Coil disk cache (256 MB)
shared with thumbnails; phase 5 will decide on a separate cache.

**Image originals** (`data/cloud/content/CloudOriginalStore`):
- Beyond 2.5x, if the displayed image exceeds the preview by more than 20% and the image itself is
  larger than the preview (`originalDecision`): **automatic download on an unmetered
  network**; on a metered network, a "Load original (size)" chip, unless the
  "Load originals on mobile data" setting is on. Cancellable progress, error with
  retry; the original replaces the preview without losing the framing.
- Download from the temporary URL (`CdnDownloader`: HTTPS outside Graph only, no
  authentication header, `Accept-Encoding: identity`, `Range` resume of a partial file of the
  same version only, version requested again from Graph just before each resume (never that
  of a cached URL; Graph does not document `If-Range`), 200 in response to a Range = restart from
  zero, new URL once after 401/403/404/410, Retry-After up to 60 s, streaming
  write, `fsync`, size checked, OkHttp call cancelled as soon as it is abandoned, including while
  reading the body).
- Space checked before downloading: if free space is insufficient but the system can
  free other apps' cache (`getAllocatableBytes`), it frees it
  (`StorageManager.allocateBytes`); otherwise "Not enough storage space".
- The `.part` file is renamed once complete, then recorded in `cached_file` (`ORIGINAL`,
  `version` = cTag) in a transaction that rechecks that the OneDrive data has not been cleared
  in the meantime and that the item is still the same (identifier, version); otherwise the file is
  deleted. An existing file with the same name (unknown version, previous original) is never
  overwritten: the new one takes another name and the old one is only deleted after the new one
  is recorded (a share already handed off stays readable). The same original is only downloaded once
  for all its requesters (viewer, sharing) and stops when nobody is waiting for it any more.
- Opaque file name (`<hash of the id>_<hash of the cTag>.<ext>`), never derived from the OneDrive
  name. An original that is already present and up to date is reused the next time it is opened.

**Sharing and "Open with"**: local file as-is; otherwise, for a signed-in account, the OneDrive
original is downloaded (cancellable progress dialog), then handed off through
`CloudGalleryFileProvider` (`${applicationId}.fileprovider`, only the `onedrive/originals/` and
`offline/` folders, read-only, display name = sanitized OneDrive name). Limits: **20 OneDrive items and 500 MB per
share** (local files do not count). CloudGallery is excluded from the chooser
(`EXTRA_EXCLUDE_COMPONENTS`) so it does not offer itself. Without a signed-in account, the OneDrive
items of a mixed selection are reported as not shared.

**Video streaming** (`data/cloud/stream/`): ExoPlayer plays the item through a stable URI
`onedrive://item/<id>`. A `ResolvingDataSource` replaces it, on every open (playback, seek,
retry), with the temporary URL, which is then read by `DefaultHttpDataSource` (Range requests
for seeking, no authentication header).
- The URL is requested from Graph (`GET /me/drive/items/{id}` without `$select`, which on personal OneDrive would omit
  `@microsoft.graph.downloadUrl`),
  kept **in memory only**, for at most two minutes (`CloudDownloadUrlProvider`), and forgotten on
  sign-out or purge.
- A 401/403/404/410 status from the download host makes the URL be forgotten: the next attempt
  gets a new one.
- Sign-in required, item deleted on OneDrive or Graph throttling: immediate failure, with a
  dedicated message (and "Sign in" leading to the Account screen if needed).
- Streaming is allowed on any network, including mobile: the user pressed Play.
- **Logs**: the URL is as good as a token. It is never logged or persisted. Media3 masks it
  in its events (the stable URI is reported), and a Media3 logger stripped of any URL
  (`RedactingMedia3Logger`) is installed at startup.

An intact offline copy (present, expected size, checked off the main thread) is played
first, before streaming and without an account (see `ui/video/PlaybackSource.kt`); "Open with"
then hands it off through the FileProvider.
All access to OneDrive content (thumbnails, playback URLs, originals) goes through `CloudAccessGuard`:
signed-in account, and the same OneDrive as the one the index data comes from. The default network state
(metered or not, Data Saver) is exposed by `data/net/NetworkStatus` for preview and
download decisions.

### Offline copies (phase 4)

"**Keep offline**" (viewer menu, or the "More" menu of a selection) downloads
a full copy of OneDrive items to the device; "**Free up space**" deletes it. These
are private app files: neither MediaStore nor the user's files are
ever touched, and no system confirmation is needed.

- **Storage**: `filesDir/offline/` (never cleared by the system, excluded from backups), opaque
  names `<sha1(id)>_<sha1(cTag)>.<ext>`. Each copy is recorded in `cached_file`
  (`OFFLINE`, `pinned`, `version` = cTag) and becomes an `OFFLINE_COPY` source: `hasOffline`, "offline"
  badge in the grid, display and playback from the copy, sharing and "Open with"
  without download (FileProvider, `<files-path name="offline">`). `primaryUri` remains reserved
  for local files: editing, rotating, cropping, deleting and copying remain impossible.
- **Download**: user-initiated data transfer job (JobScheduler
  `setUserInitiated`, "offline" namespace), with no quota or 6 h limit; WorkManager 2.12 cannot
  create this kind of job. One item at a time (`OfflineDownloadEngine`): `.part` file
  resumed through HTTP Range for the same version, `fd.sync()`, size checked, rename, then
  recording in a transaction that rechecks that the OneDrive data has not been purged,
  that the task has been neither freed nor cancelled and that the item is still in the index with the
  same version. Connections use the network chosen by the system (`JobParameters.getNetwork`).
  "Free up space" or "Cancel" on an item in progress only stops that item (the pass continues
  with the next ones); a task deleted during its download interrupts it at the next progress
  update. Before finishing, the job rechecks the queue: a task added at that
  moment is picked up by a new pass or handed to a new job (in-process job tracking,
  `OfflineJobTracker`: JobScheduler still returns a job that has just finished).
- **Network**: "**Download offline on Wi-Fi only**" setting (on by default,
  Settings); on a mobile network, a dialog offers "**Download now**" (also on the
  Account screen), which lifts the restriction **only for the tasks already queued at that
  moment**: tasks added afterwards (including new versions found by the sync)
  follow the setting again. The network is checked before each item; changing the setting
  immediately reschedules the running or scheduled job (on: a pass on a mobile network stops
  and waits for Wi-Fi; off: a job that was waiting for Wi-Fi starts).
- **Notification**: progress (current item, remaining items) with a "Cancel" button,
  then a summary. The permission is requested on the first "Keep offline"; if denied, the
  downloads continue (progress in the app).
- **Errors**: network, throttling, server error: retried later (backoff managed by the
  system, 5 attempts; a network drop after bytes were received does not count); content more
  recent on OneDrive than in the index: the task waits for the next sync without counting an
  attempt or blocking the following ones; item not found: that item fails; account signed out
  or not enough space: the whole queue fails. A failure can be resumed from the viewer or the Account
  screen.
- **Space**: checked before queuing (`StorageManager.getAllocatableBytes`, 200 MB margin);
  if space is short, the system "Free up space" screen is offered. At the start of each
  pass, if free space is not enough for the queue but other apps' cache can
  be freed, the system is asked to free it (`allocateBytes`, a single request per pass).
  A batch of more than **200 items or 1 GB** asks for confirmation (total size and available
  space).
- **Stopping**: a stop from the Task Manager (or a force stop during a
  download) **pauses** the queue until the user resumes it; swiping the
  app away from recent apps with no download in progress changes nothing; a
  stop caused by loss of the network or Wi-Fi resumes automatically. The job is not persisted:
  after a reboot, the queue resumes the next time the app is opened.
- **New version on OneDrive**: the sync queues a new download (Wi-Fi rule
  honored, launched in the foreground); the old copy stays displayed until the new one
  replaces it. If this update is paused or has failed, Details reports it ("new
  version not downloaded" and its cause) and the viewer offers "Resume download"
  and "Cancel download" for that item.
- **Item deleted from OneDrive**: its copy is hidden along with it (also for a deleted folder
  or a full enumeration) and reappears if the item is restored. At the end of the 30-day grace period, an
  item that has an offline copy **is not purged**: the Account screen shows "N offline copies
  of items deleted from OneDrive" (with their previews) and the Menu shows a badge.
  "**Delete the copies**" (with confirmation) removes them; "**Keep them**" keeps them, visible,
  as the only remaining copies ("deleted from OneDrive" in Details): after that they only go
  through "Free up space", with a confirmation that flags the last copy. Both actions
  only apply to the list that was displayed and confirmed, rechecked within their transaction: an item
  that came back on OneDrive in the meantime is not touched, an item that reached the end of the 30 days after
  the display waits for the next decision. If the item comes back on OneDrive, it is reattached to the
  same media item (favorites and albums kept). Items without a copy are purged as before.
- **Last copies**: "Free up space" counts the last copies (no visible OneDrive item and no
  local file) in the selection, hidden copies included (item deleted from OneDrive from another
  device while the selection was open): confirmation as soon as there is one. If this
  count fails, nothing is deleted (message). The confirmations for sign-out, removal of
  leftover data and account switch can only be accepted once the number of
  copies (and of last copies) is known; if it could not be read, they warn that some
  copies may be the last ones.
- **Account screen**: "Offline" section (in progress, waiting for Wi-Fi, paused, failures,
  copies present and size), "Download now", "Resume downloads", "Cancel
  downloads".

### Cache (phase 4-5)

> **Phase 4: temporary cache of originals and offline copies.** LRU and Storage page: **planned
> (phase 5).**

- **Originals (phase 4)**: `cacheDir/onedrive/originals/` (never `cacheDir/coil`), capped at
  **1 GB**; the first time the app comes to the foreground, originals unused for
  more than **7 days** are deleted, then the oldest ones beyond the cap, as well as rows
  without a file, files without a row (older than one hour) and abandoned partial files (older
  than 24 h) (pure rules: `planOriginalTrim`, `filesToSweep`). A row is only deleted if it
  has not changed since it was read (original reopened or replaced in the meantime: kept), and never
  while an original is being read by the viewer or a share. Android can also clear this folder when space runs low. Sign-out, purging
  leftover data and switching accounts delete all originals.
- Files are tracked in `cached_file` (one per media item and per level), with `lastAccessAt`.
- **LRU eviction** under a configurable cap: the least recently accessed files are
  deleted first. **`pinned` files ("Keep offline") are never evicted.**
- Offline copies (phase 4) live in `filesDir/offline/`, outside this cache, and are
  never evicted (see "Offline copies"). The first time the app comes to the foreground, files
  without a row (older than one hour) and abandoned partial files (older than 24 h, excluding open
  tasks) are deleted from it.
- A "Storage" page will show the space used per level, the cap and a clear button
  (non-pinned files). This cache only holds copies of OneDrive files: the user's files
  are never affected.

### Caching all of OneDrive

Settings > Storage > "Cache all of OneDrive": for each item whose thumbnail
comes from OneDrive (no file on the phone), the **grid tile** (default zoom step
for this screen) and the **viewer preview** (same step as the viewer on
Wi-Fi, 1600 px for videos) are downloaded once. Full videos and originals
stay online.

- **Storage**: `noBackupFilesDir/onedrive_precache/<2 chars>/<SHA-1(id + cTag)>.g<step>` or
  `.p<side>`, outside the Coil cache and its LRU cap, never evicted automatically. The thumbnail
  loader (`CloudThumbnailFetcher`) reads it after the Coil cache: equal or larger size
  first; offline or on failure, a smaller size rather than an empty cell.
- **Execution**: WorkManager (`OneDrivePrecacheWorker`, unique work `onedrive_precache`), as a
  `dataSync` foreground service when launched with the app visible (otherwise ordinary work,
  resumed by WorkManager). Wi-Fi if the offline copies' "Wi-Fi only" setting is on,
  battery and storage not low, 3 parallel downloads, Graph global pause honored.
  Resumes without re-downloading (files already present are skipped); stops below 2 GB free (or 5% of the
  volume), on sign-out, and pauses after 20 consecutive network failures or Graph throttling.
  The 6 h limit of `dataSync` services (Android 15) at worst interrupts one pass, which is resumed afterwards.
- **Estimate and progress**: number of items to download and estimated size (≈ 35 KB per
  tile, 350 KB per preview) before confirmation; progress on the page and in the notification.
- **Keep up to date** (off by default): after each successful sync, a pass is
  queued in the background. Otherwise, only a network-free cleanup removes the files of items that
  disappeared, changed (new cTag) or are now stored on the phone.
- **Clearing**: "Clear cache" stops the passes, deletes everything and turns off "Keep up to date";
  sign-out, purge and account switch also delete everything.

### Search (phase 6)

- `media_fts` full-text index (FTS4, unicode61 tokenizer: case and accents ignored), one entry
  per logical media item (`rowid` = `media.id`): file name; folders of the visible sources, virtual
  albums that contain it and custom names of folder albums; year, month and day of the
  week in French and English, day of the month, "vidéo video" or "photo image".
- `SearchIndexer` watches the `media`, `media_source`, `album` and `album_media` tables (Room's
  InvalidationTracker): 2 s after the last write, whatever its origin (MediaStore indexing,
  OneDrive sync, purge, rename), an incremental update in a single transaction removes
  deleted or stale entries and adds the missing ones. For 100,000 media items, on the order of one to
  a few seconds on the I/O dispatcher (the first fill, after the v6 migration, writes
  all entries).
- Input → safe FTS expression (`ftsMatchExpression`): words (letters and digits) in lowercase,
  each as a prefix (`word*`), implicit AND; no FTS syntax character or operator gets through.
- Results: visible media (at least one present source), items stored only on OneDrive
  included regardless of the Photos setting, newest first; shared grid
  (selection, sharing, albums…) and a viewer limited to the results (`MediaContext.Search`).
- Recent searches (at most 10) in `search_history.xml`, excluded from backups.

### Space saver (phase 6)

Settings > Storage > Space saver (`ui/saver/`). The only place where the app offers to
delete files from the phone to free up space; never launched automatically, no
notification.

- **Offered copies** (`MediaSourceDao.spaceSaverCandidates`, `SPACE_SAVER_SELECT`): a visible local
  source whose QuickXorHash, computed by the app, is up to date (`hashSignature` equal to
  the file's `"<sizeBytes>:<dateModified>:<generation>"`) and identical, size included, to that of a
  visible OneDrive item. The rules apply to sources: the copy and the OneDrive item may or may not be grouped
  in the same media item. A copy that is a favorite or belongs to an album is only offered once
  grouped with the OneDrive item (otherwise the favorite or album would be lost); excluded copies
  are counted on screen.
- **Review**: age filter (capture date: all, more than 30 days by default, 90 days,
  one year), all copies in the filter checked, the user unchecks those to keep; summary (count,
  space freed).
- **Deletion**: in-app confirmation, recheck in the database right before (copy modified,
  re-identified or OneDrive item gone: excluded and reported), then
  `MediaStore.createTrashRequest` in batches of 1,000 URIs (one request goes through a single Binder
  transaction), each batch confirmed by the system dialog. **Always the Android trash** (recoverable
  for 30 days), whatever the deletion setting. A refusal stops the remaining batches. Then:
  MediaStore and the list are reread.

### Samsung Secure Folder

"Move to Secure Folder" ("More" menu of the selection and of the viewer,
`ui/securefolder/`) only appears if the `com.samsung.knox.securefolder` app is installed, and
only for files on the phone. Since Secure Folder has no public API, the app
goes through the **Android share sheet**: the user picks a Secure Folder app there
(for example its Gallery; One UI lets these apps be shown as share targets from the
Secure Folder settings). On returning to CloudGallery, the app asks whether the addition succeeded:
"Keep originals" deletes nothing, "Move originals to trash" launches
`MediaStore.createTrashRequest`, confirmed by the system (**always the trash**). At most 100 items
per transfer. A file grouped with OneDrive says so: the OneDrive copy is not
modified and stays visible in the app.

---

## Microsoft Entra configuration and MSAL setup

> **Required to enable OneDrive.** Without this configuration, the app builds and
> works normally: the OneDrive entries are simply hidden (no dead button).

### 1. Register the app in Microsoft Entra

1. Open the **Microsoft Entra admin center** (<https://entra.microsoft.com>) with an
   account that has a directory (tenant). A personal Microsoft account on its own may require
   creating a tenant, for example through a free Azure account.
2. **Identity > Applications > App registrations > New registration.**
   - Name: `CloudGallery`.
   - Supported account types: **"Personal Microsoft accounts only"** (or "Accounts
     in any organizational directory and personal Microsoft accounts" to also accept
     work accounts).
   - Do not enter a redirect URI at this step.
3. Note the **Application (client) ID** on the Overview page.
4. **Authentication > Add a platform > Android.**
   - Package name: `fr.eva.cloudgallery`.
   - Signature hash: see step 2 below.
   - The portal generates the redirect URI
     `msauth://fr.eva.cloudgallery/<signature hash in base64, URL-encoded>` (the base64 `/`, `+` and
     `=` become `%2F`, `%2B` and `%3D`). The portal also provides the matching MSAL
     configuration snippet.
5. **API permissions > Add a permission > Microsoft Graph > Delegated
   permissions**: `Files.Read`, `User.Read` and `offline_access`. For a personal account,
   consent is given by the user on first sign-in.
6. **No client secret or certificate**: a mobile app is a *public client*, and a
   secret embedded in an APK protects nothing.

### 2. Get the signature hash (debug keystore, Windows)

The debug keystore is located at `%USERPROFILE%\.android\debug.keystore` (alias `androiddebugkey`,
password `android`). The expected hash is the **SHA-1 of the certificate, base64-encoded**.

In **Git Bash**, which provides `openssl`:

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android \
  | openssl sha1 -binary | openssl base64
```

Without OpenSSL, in **PowerShell**:

```powershell
$sha1 = (keytool -list -v -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android |
  Select-String 'SHA1:').ToString().Split(' ')[-1]
[Convert]::ToBase64String(($sha1 -split ':' | ForEach-Object { [Convert]::ToByte($_, 16) }))
```

`keytool` ships with Android Studio's JDK (`<Android Studio>\jbr\bin\keytool.exe`). For a
release APK signed with a release key (see [Building](#building)), compute the hash of that key
the same way, add it in Entra and pass it as `msal.releaseSignatureHash`.

### 3. Configure the project

1. Add to **`local.properties`** (at the root, not versioned; never commit it):

   ```properties
   msal.clientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   msal.signatureHash=XXXXXXXXXXXXXXXXXXXXXXXXXXX=
   ```

   - `msal.clientId`: the Application (client) ID from step 1 (GUID format).
   - `msal.signatureHash`: the hash from step 2, **as-is** (28-character base64, not
     URL-encoded).
   - The same keys can be given as Gradle properties (`~/.gradle/gradle.properties`, or
     `-Pmsal.clientId=… -Pmsal.signatureHash=…` on the command line), which take precedence over
     `local.properties`. Handy if Android Studio rewrites `local.properties`.
2. Rebuild (`./gradlew assembleDebug`). A missing or malformed value does not prevent the
   build: Gradle prints "MSAL not configured (msal.clientId / msal.signatureHash): OneDrive
   disabled in this build" and the app hides all OneDrive entries.
3. On the phone: Menu > OneDrive (or Settings > Account > OneDrive) > "Sign in to
   OneDrive". The first sync starts right after sign-in.

What the build does (nothing to write by hand):

- `BuildConfig.MSAL_CLIENT_ID` and `BuildConfig.MSAL_SIGNATURE_HASH` (`buildConfigField`), validated
  with a regular expression.
- Manifest: `com.microsoft.identity.client.BrowserTabActivity` with an intent filter
  `scheme="msauth"`, `host="${applicationId}"` and `path="/<hash>"` (**not encoded**, through the
  `msalSignatureHash` placeholder; dummy value `msal-not-configured` if missing).
- MSAL configuration **generated at runtime** (no `res/raw` file): on first use, on the
  I/O thread, `noBackupFilesDir/msal_config.json` is written with `client_id`, `redirect_uri`
  (**URL-encoded** form: `msauth://fr.eva.cloudgallery/<encoded hash>`),
  `account_mode: "SINGLE"`, `authorization_user_agent: "DEFAULT"`,
  `broker_redirect_uri_registered: false` and the AAD authority `PersonalMicrosoftAccount`
  (`consumers`), then passed to `PublicClientApplication.createSingleAccountPublicClientApplication`.
- A public client's client ID is not a secret, but it is specific to each registration and
  is never versioned. **No secret is needed.**

---

## Building

Prerequisites:

- A recent **Android Studio**, compatible with AGP 9.4.1. It bundles the JDK (JetBrains Runtime)
  used by Gradle.
- **JDK 17 or later** for Gradle (the code is compiled targeting Java 17). Android Studio's JBR
  works.
- Android SDK with the **API 37** platform (`compileSdk 37`). The SDK path is in
  `local.properties` (`sdk.dir`).

From Android Studio: *Build > Assemble Project*, or run the `app` configuration on a
connected device.

From the command line, at the project root:

```powershell
.\gradlew.bat assembleDebug      # debug APK
.\gradlew.bat assembleRelease    # release APK (R8 enabled)
```

(`./gradlew assembleDebug` in Git Bash.)

Output APKs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

**Release signing**: the `release` build is signed with the keystore designated by the
`cloudgallery.release.storeFile`, `cloudgallery.release.storePassword`,
`cloudgallery.release.keyAlias` and `cloudgallery.release.keyPassword` properties (in
`~/.gradle/gradle.properties` or `local.properties`, never in the repository). Without these properties, it
is signed with the machine's debug key. The release key's hash is passed to MSAL through
`msal.releaseSignatureHash` and must be registered in Entra in addition to the debug one (same
Android platform, second hash). An APK signed with a different key cannot update
the existing installation without uninstalling first, which wipes the local database: favorites and
custom albums would be lost.

---

## Installing on a Samsung Galaxy S22

The app requires **Android 16** (`minSdk 36`), i.e. **One UI 8**.

### With adb (recommended)

1. On the phone: *Settings > About phone > Software information*, tap
   "Build number" 7 times to enable developer options, then *Developer
   options > USB debugging*.
2. Plug in the phone, accept the PC's fingerprint, then:

```powershell
adb devices
adb install -r app\build\outputs\apk\release\app-release.apk
```

`-r` reinstalls while keeping the data (database, favorites, albums, settings).

### Without adb

Copy the APK to the phone (cable, OneDrive, email…), open it from *My Files* and
allow installing unknown apps for that source app when Android
asks (*Settings > Apps > Special access > Install unknown apps*).

### Making CloudGallery the default photos app

- Open a photo or video from another app (My Files, a messaging app…),
  choose **CloudGallery** then **Always**.
- If Samsung Gallery had already been chosen as the default: *Settings > Apps > Gallery >
  Set as default > Clear defaults*, then repeat the previous step.
- The app also declares the `APP_GALLERY` category, used by apps that
  "open the gallery". The Samsung camera may keep opening its own gallery from
  the capture thumbnail (behavior to be checked on the device).

### Samsung Gallery

Samsung Gallery is a system app: it **cannot be uninstalled**, only
**disabled** (*Settings > Apps > Gallery > Disable*, if the button is offered), or
through `adb shell pm disable-user --user 0 com.sec.android.gallery3d` (re-enable:
`adb shell pm enable com.sec.android.gallery3d`). Disabling it does not delete any file.
Before disabling Samsung Gallery, empty or restore its trash and check any
syncs it may have (OneDrive / Samsung Cloud). It does not need to be disabled to use
CloudGallery.

---

## Tests

### JVM unit tests (`app/src/test`)

```powershell
.\gradlew.bat testDebugUnitTest
```

| File | Contents |
|---|---|
| `data/local/MediaStoreLogicTest` | External identifiers, dates, full-reread decision, fingerprints, parsing of MediaStore / MediaDocuments URIs, cropping, playback positions |
| `data/local/RemovalPlanTest` | Removal plan: partial access, hiding, mass-deletion brake, 30-day grace period |
| `data/prefs/SettingsLogicTest` | Zoom level clamping, fallback to the default value for an unknown enum, "Show OneDrive items in Photos" on and "Load originals on mobile data" off by default, "Wi-Fi only" on by default (empty DataStore) |
| `data/repository/AlbumSortingTest` | Album sorting |
| `data/search/SearchLogicTest` | Safe FTS expression (prefixes, implicit AND, no FTS syntax or operator, bounds), date labels consistent with the index SQL, name matching (accents, case, word starts), search history |
| `ui/search/SearchUiLogicTest` | Suggested albums (matching, empty albums excluded, bound), filtered recent searches, input without a word rejected |
| `ui/albums/AlbumsLogicTest` | Columns, hiding empty folders, selection actions (rename, delete, pin), names |
| `ui/grid/GridSelectionLogicTest` | Selection (ranges, day/month), targets of actions on local files (OneDrive items excluded, confirmation counts), Share enabled for OneDrive items, "Keep offline" / "Free up space" entries |
| `ui/offline/OfflineUiLogicTest` | Outcomes of "Keep offline" (message, waiting for Wi-Fi, large batch, not enough space), "Free up space" confirmation for a last copy only, abort if counting the last copies fails, purge confirmations (button disabled while reading, cautious warning if it fails), menu entries |
| `data/cloud/offline/OfflineLogicTest` | Size to download, missing space (margin), freeing other apps' cache (`allocateBytes` only if free space is short but obtainable), large batches (200 items / 1 GB), job network (Wi-Fi, "Download now" limited to its tasks, running job that covers or not), new version to re-download, identical content at save time (unknown version never identical), displayed state (stopped update reported), outcome of a failure (retry, failure, whole queue, waiting for the sync without counting an attempt, drop after progress not counted), job stop (pause, cancellation, resume), pause at startup only after a stop during a download, end of grace period with a copy (decision D1), safe file names |
| `data/cloud/offline/OfflineJobTrackerTest` | Scheduling: running job that takes over the tasks, scheduled job ignored if it has just finished, old replaced job that finishes after the new one |
| `data/cloud/content/CloudOriginalLogicTest` | Original cleanup (rows without a file, older than 7 days, least recently used beyond the cap, ongoing downloads spared), replacement name of an original |
| `ui/util/ShareLogicTest` | Share plan (local file, offline copy, download), 20 items / 500 MB limits, OneDrive items without an account, progress percentage |
| `ui/grid/GridSeparatorsTest` | Day/month headers |
| `ui/grid/GridZoomLevelsTest` | Zoom levels, columns, grouping |
| `ui/grid/FastScrollLogicTest` | Fast scroll: rank of a date or period, handle fraction, bubble label |
| `ui/video/VideoPlayerLogicTest` | Classification of playback errors (network, link, OneDrive failures found in the cause chain), "Retry" / "Sign in" actions, resume position, clamped seek, double-tap zones, speeds, aspect ratio |
| `ui/video/PlaybackSourceResolverTest` | Priority of playback sources (local file, intact offline copy, then OneDrive streaming if the account is accessible), truncated copy ignored, reason for unavailability |
| `ui/viewer/CropMathTest` | Crop geometry |
| `ui/viewer/ExifFormatTest` | EXIF data formatting |
| `ui/viewer/ZoomMathTest` | Viewer zoom calculations, dimensions read from the file when the index has none (high-resolution reload possible) |
| `ui/viewer/ViewerSummaryTest` | Thumbnail strip: summary update (favorite, version, OneDrive thumbnail, offline copy) |
| `data/cloud/auth/CloudAuthLogicTest` | Validation of the client ID and hash, encoded redirect URI, MSAL JSON configuration, token freshness, classification of MSAL errors (offline ≠ sign-in required) |
| `data/cloud/graph/GraphCallerTest` | Graph request loop with a fake transport: token only sent to Graph, 401, 429/503 with Retry-After or exponential backoff, 410, network errors, global pause (applied even after giving up, immediate failure beyond 10 min), rejected renewed token reported, Accept header, thumbnail retry budget |
| `data/cloud/graph/GraphRetryLogicTest` | Classification of HTTP statuses, Retry-After, exponential backoff, resume URL, trusted URL, initial delta URL, URLs for thumbnails and for the download URL (without `$select`), HTTPS download URL outside Graph |
| `data/cloud/graph/GraphDtoParsingTest` | Parsing of Graph responses (delta pages, deleted items, folders, packages, unknown fields, `@microsoft.graph.downloadUrl` never shown by `toString`) |
| `data/cloud/stream/CloudStreamLogicTest` | Streaming failure causes (Graph, MSAL, network), retry decision (expired URL, immediate failure), bounded cause chain, stable URI, redaction of URLs and `tempauth` in logs |
| `data/cloud/transfer/TransferLogicTest` | Reuse duration of a download URL, expired-URL statuses, `Content-Range`, classification of Range responses (206, 200, 416), resume plan (unknown version never identical), opaque file names and extensions, safe display name (separators, reserved and control characters, Unicode, length, empty), progress cadence, same OneDrive drive (case ignored), cleanup of the copy folders (saved or in-progress files never touched, partial files after 24 h, orphans after 1 h) |
| `data/cloud/transfer/CdnDownloaderTest` | Download with a fake transport: complete 200, 206 resume (version requested again from Graph before any resume, changed content rejected before appending bytes), 200 after Range (restart from zero), already complete file without a request, unexpected 416, unknown version without resume, expired URL renewed once, wrong size, non-HTTPS or Graph URL rejected, changed content, 429/503 with Retry-After, Graph errors, no `Authorization` header, `Accept-Encoding: identity`, cancellation while reading the body (call cancelled), never a URL in a message |
| `data/cloud/transfer/CloudDownloadUrlProviderTest` | In-memory URL cache: reuse, forced renewal, forgetting, access denied without a request, purge during a request (nothing put back in the cache), bounded size |
| `data/net/NetworkLogicTest` | Metered network or not, Data Saver (exempted app not affected), unknown state treated as metered |
| `data/cloud/sync/OneDriveLogicTest` | Classification of delta items, MIME type, dates, OneDrive album paths and names, hide/purge plan, foreground interval, cursor tied to the drive, start decision (incremental, full, different account), what follows a pass after a page (restored folder: immediate full enumeration), Graph pause reread at startup |
| `data/cloud/thumb/CloudThumbnailLogicTest` | Size steps, Graph sizes and fallback chain (preview 2048 → 1600 → `large`), cache keys per preview step, preview step depending on screen, image and network, decision to load the original (automatic, to offer, unnecessary), thumbnails not found (expiry) |
| `data/mapper/CloudThumbMappingTest` | OneDrive thumbnail reference for grid rows and album covers |
| `ExampleUnitTest` | Android Studio template test |

### Instrumented tests (`app/src/androidTest`): device or emulator required

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

- `data/db/MediaDaoTest`: in-memory Room database. Pagination order, favorites projection,
  album membership, orphan cleanup, recomputation of denormalized columns, hiding
  a source while keeping favorites and albums, re-identification, identifier ranges,
  protection of custom albums, hidden OneDrive sources, "OneDrive in Photos" filter.
- `data/db/MigrationTest`: migrations 2→3, 1→3, 3→4, 2→4 and 1→4 with `MigrationTestHelper`
  (exported schemas added to the instrumented test assets); in 3→4, rows kept,
  new columns null and `attempts` at 0.
- `data/db/SearchDaoTest`: search index (name, folder, album, dates and day of the week,
  type, accents), incremental update (rename, album, deletion), results limited to
  visible media (OneDrive included, hidden sources excluded).
- `data/db/CachedFileDaoTest`: replacing a copy (same row, old row returned),
  access date, deletions, totals, cascade, number of shareable items, conditional
  deletion during cleanup (reopened or replaced row kept).
- `data/db/OfflineDaoTest`: only one open task per media item (`enqueueIfAbsent`, new
  version, resuming a paused or failed task, running task unchanged), queue order,
  resume after the process is killed, pause and resume, cascade; offline copy (`hasOffline`,
  never `primaryUri`, visible in Photos with the setting off), visibility aligned with the OneDrive
  item, last copies (hidden copies included, per selection), items awaiting a
  decision and recheck of a confirmed list, deletion of hidden sources only, downloadable
  tasks (changed content waiting for the sync), progress of a task that disappeared,
  candidates for "Keep offline" (items already downloading excluded, stopped update
  resumable).
- `data/cloud/offline/OfflineDownloadEngineTest`: engine with a fake downloader. Recording
  (copy, source, task removed), purge during the download, freeing before
  recording (nothing recreated), new version (requeued), item gone (failure then
  next item), account signed out, network error (partial file kept), "running" tasks
  of a dead process, pause only after a stop during a download, stop then
  new pass (only one at a time), replacement of an old copy (old file deleted),
  content newer than the index (waiting for the sync without counting an attempt, queue not blocked),
  network drop after progress, tasks allowed on the network ("Download now"),
  "Free up space" on the item in progress (only that item stops), task gone during the
  download, space (other apps' cache freed only when needed).
- `data/cloud/offline/OfflineRepositoryTest`: decision D1 on the confirmed list only
  (unconfirmed item, still in the grace period or back on OneDrive never touched), "Keep
  them" (copy visible, hidden OneDrive item no longer there), "Free up space" on a copy kept
  alone (last copy: item removed with its file) or on an ordinary copy (OneDrive item
  kept), hidden last copies counted.
- `data/cloud/sync/CloudSyncWriterTest`: writing delta pages (creation, new cTag,
  deletion then purge, folder deleted then restored, deleted folder without a `folder` facet,
  restore detected for subfolders and deltaLink cleared with the page (interrupted
  pass), full enumeration, renamed OneDrive albums), local rows never touched;
  offline copies hidden with their item (deletion, deleted folder, full
  enumeration), shown again on restore, kept beyond the grace period (decision D1), only one
  new download per new version (stale temporary original removed), kept copy
  reattached when the item comes back.
- `data/cloud/CloudDataPurgerTest`: complete removal of OneDrive data (including hidden
  sources, covers, thumbnails, rows and files of downloaded originals, offline copies,
  download queue, clear counter incremented), local data intact.
- `ExampleInstrumentedTest`: template test.

The MediaStore indexer, the Compose UI, the video player, MSAL and real calls to Microsoft Graph
have no automated tests: they are verified on the device.

### On-device checks: OneDrive (phase 3)

To be done on the Galaxy S22 with a configured build (see
[Microsoft Entra configuration](#microsoft-entra-configuration-and-msal-setup)):

| Check | Expected result |
|---|---|
| Build without `msal.clientId` / `msal.signatureHash` | Build succeeds, no OneDrive entry in Menu or Settings |
| Sign-in (Menu > OneDrive > Sign in) | Microsoft browser tab, return to the app, account name shown, sync started |
| Force stop then relaunch | Account still signed in, foreground sync without signing in again |
| First sync | OneDrive albums created (one per folder containing media), Graph thumbnails in the albums |
| "Show OneDrive items in Photos" setting | Off: Photos and Videos without OneDrive-only items (counts included); on: items mixed |
| Second return to the foreground within 5 min | No request; after 5 min, a single delta call |
| Photo and folder deleted on OneDrive (web), then "Sync now" | Items hidden; `deleted`, `root`, `parentReference.id`, `file.hashes.quickXorHash` and `photo` correctly received with the `$select` (otherwise change `DELTA_SELECT` and `DELTA_SELECT_VERSION`) |
| Folder restored from the OneDrive recycle bin | Automatic full enumeration, contents visible again |
| Renaming / moving a folder on OneDrive | Album renamed, path updated |
| `c{n}x{n}_crop` thumbnail sizes | Sharp thumbnails; if Graph rejects them, automatic fallback to the standard sizes |
| Airplane mode after a first display | Thumbnails already seen still shown (disk cache); preview not seen: explanatory message |
| Viewer on a OneDrive-only item | Preview with caption, play button and duration badge for a video, no rotation, cropping, copy or deletion (sharing and "Open with": see phase 4) |
| OneDrive-only selection (OneDrive album) | Delete and Copy disabled; Share (phase 4), Favorite and Add to album enabled |
| Mixed selection, Delete | Title with the number of local files, mention of the unaffected OneDrive items; only local files go to the trash |
| Sign-in with a different Microsoft account | "Different OneDrive account" card, nothing purged before confirmation |
| Sign-out (confirmed) | OneDrive items and albums removed, OneDrive thumbnails cleared (`cache/coil`), local media intact |
| Session revoked (password changed) | "Sign-in required", data kept, signing in again possible |
| Sign-out while thumbnails are loading (slow network) | Account still "Not signed in" afterwards, no sync relaunched |
| Folder containing a photo subfolder deleted then restored (OneDrive recycle bin) | Full enumeration, subfolder photos visible again |
| Logcat (`adb logcat`) | No token, deltaLink or account name |
| `adb shell run-as fr.eva.cloudgallery ls -R shared_prefs files app_fr.eva.cloudgallery` | MSAL file names (including `adalks`) consistent with the backup exclusions |

### On-device checks: OneDrive streaming (phase 4)

| Check | Expected result |
|---|---|
| Playing a OneDrive-only video | Streamed playback; on failure, player error message (network, link, sign-in required…) |
| Seek in a long video | Fast resume (206 response from the download host to Range requests) |
| Pause of more than two minutes then seek | New URL obtained without error; note the status returned for an expired URL |
| Airplane mode during playback | "Couldn't connect" message, "Retry" restarts at the same position |
| OneDrive sign-out then playback | Sign-in message and "Sign in" button; back to the player after signing in: playback |
| Video deleted on OneDrive (before the sync) | "This video can't be found on OneDrive", no "Retry" button |
| Logcat during playback and after an error | No `files.1drv.com` or `tempauth` URL (URLs replaced with `<url>`) |

### On-device checks: OneDrive previews, originals and sharing (phase 4)

| Check | Expected result |
|---|---|
| OneDrive-only image on Wi-Fi | Sharp preview (2048 step); if Graph rejects `c2048x2048`, fallback to `c1600x1600` with no visible error |
| Zoom beyond 2.5x on Wi-Fi | Progress chip, then original shown with no framing jump |
| Same zoom on mobile data (or Data Saver) | "Load original (x MB)" chip; nothing is downloaded before tapping; setting on: automatic loading |
| 50 MP original (Galaxy S22) from OneDrive | Displayed without crashing (decoding reduced to ~24 MP at most) |
| Cancellation during the download (chip) | Back to the preview; retry possible |
| Sharing a OneDrive image to Gmail or Messages | Progress dialog, then attachment with the OneDrive name (not the internal name) |
| "Open with" on a OneDrive image or video | CloudGallery absent from the chooser; the chosen app opens the file |
| Selecting 21 OneDrive items, Share | "You can share up to 20 OneDrive items at a time" message |
| OneDrive video larger than 500 MB, Share | "Too large to share from OneDrive (up to 500 MB per share)" message, no download |
| OneDrive sign-out after a few originals | `adb shell run-as fr.eva.cloudgallery ls cache/onedrive/originals` empty |
| Eight days without opening an original, then app relaunch | Original deleted from the folder (foreground cleanup) |
| Logcat during downloads | No `files.1drv.com` or `tempauth` URL |
| `MigrationTest` 3→4 and `CachedFileDaoTest` (`connectedDebugAndroidTest`) | Pass on the device |

### On-device checks: offline copies (phase 4)

| Check | Expected result |
|---|---|
| First "Keep offline" | Notification permission request (only once); if denied, the download still happens |
| "Keep offline" on Wi-Fi | Progress notification with "Cancel", "offline" badge in the grid, item visible in Photos with the OneDrive setting off |
| Same action on mobile data (Wi-Fi only on) | "Waiting for Wi-Fi" dialog; "Download now" starts the download on mobile |
| Wi-Fi lost during a download (Wi-Fi only) | Stops, then resumes automatically when Wi-Fi returns (Range request, partial file kept), never on mobile |
| VPN active | The download starts and completes |
| Airplane mode after download | Image and video shown and played from the copy; sharing and "Open with" without network |
| Stop from the Task Manager during a download | Queue "paused" (Account screen), no automatic resume; "Resume downloads" restarts it |
| "Cancel" button in the notification | Queue cleared, copies already present kept, no `.part` file left |
| Phone reboot with a pending queue | Resumes the next time the app is opened |
| Photo modified on OneDrive, sync | New download; the old copy stays displayed until replaced |
| Selecting more than 200 items (or more than 1 GB) | Confirmation with the total size and available space |
| Not enough space | "Not enough space" dialog and the system "Free up space" screen |
| "Free up space" | Copy deleted without confirmation, badge removed; item still on OneDrive |
| Kept item deleted on OneDrive | Hidden; after 30 days (or clock moved forward), Account screen card and Menu badge; "Keep them" makes it visible ("deleted from OneDrive"), "Free up space" then asks for confirmation |
| Sign-out with offline copies | The confirmation states the number of copies deleted (and the last copies); `adb shell run-as fr.eva.cloudgallery ls files/offline` empty |
| Data Saver on, "Download now" on mobile | Download allowed (user-initiated job) |
| One UI "sleeping apps" restrictions | The download started in the foreground completes (note the behavior) |
| `adb shell dumpsys jobscheduler fr.eva.cloudgallery` | `offline` job 4001 "user-initiated", unmetered network (or any after "Download now") |
| Logcat during downloads | No `files.1drv.com` or `tempauth` URL, no file name |
| `connectedDebugAndroidTest` | `OfflineDaoTest`, `OfflineDownloadEngineTest`, `OfflineRepositoryTest`, `CloudSyncWriterTest`, `CloudDataPurgerTest` pass |

---

## Known limitations

- **Read-only OneDrive**: an item stored only on OneDrive can be displayed
  (preview, original on deep zoom), streamed, shared or opened in another app
  (original downloaded, 20 items / 500 MB per share, signed-in account required), but not modified,
  deleted or copied to a folder on the phone, even when kept offline. No Storage page
  yet (phase 5). The EXIF information of a downloaded original or of an offline copy is
  not read (Details panel limited to OneDrive metadata).
- **Offline copies**: downloaded only when the app launches them in the foreground
  (user-initiated job); a new version detected by the sync waits for the
  next return to the foreground. After a phone reboot, the queue resumes the
  next time the app is opened. An item kept offline that also exists on the phone is merged
  with it once the local hash is computed. Membership in OneDrive albums
  of a copy kept after deletion from OneDrive is lost (favorites and personal albums
  kept). The preview image of a video copy comes from the file (no OneDrive thumbnail);
  album covers do not use offline copies yet.
- **Personal Microsoft accounts only**, one account at a time. The contents of the Personal
  Vault are not supported (behavior of the delta query not
  verified); items shared by other people are ignored.
- **Duplicates**: a phone file and its OneDrive item are only merged after the
  local hash is computed, which happens only while charging (first computation spread over several
  charges for a large library). Until then they are two media items, both visible in Photos
  with the "Show OneDrive items in Photos" setting (on by default). A file modified after its
  backup (different content) is never merged.
- **OneDrive without media permission**: without access to the phone's photos, the app shows
  the permission screen and nothing else; a "OneDrive only" usage is deferred to phase 5.
- **OneDrive dates**: `photo.takenDateTime` may be the EXIF local time presented as UTC; a
  OneDrive item may then be sorted a few hours away from its local copy.
- **First sync of a large library**: if the app is killed during the first
  full enumeration, it starts over from the beginning on the next pass (idempotent
  writes, no loss).
- **No background sync**: the local index and OneDrive are only updated while
  the app is in the foreground (OneDrive: at most every 5 minutes, or "Sync
  now"). WorkManager is declared but not used.
- **App-specific favorites and custom albums**: they are not read from Samsung
  Gallery nor written to MediaStore's `IS_FAVORITE` field, and the database is excluded from backups.
  Uninstalling or changing devices loses them.
- **Search** by name, folder, album, date and type only (neither by place nor by content).
- **Secure Folder**: the transfer depends on Samsung (Secure Folder apps visible in the
  share menu, Samsung's own import limits); the app cannot verify that the
  files arrived, so the user confirms it before the trash.
- **Deduplication**: QuickXorHash (`data/dedup/QuickXorHash`, same format as
  Graph's `file.hashes.quickXorHash`) computed for each file on the phone by
  `LocalHashWorker` (WorkManager, **while charging** and battery not low, batches of 100 files / 1 GB,
  8 min per pass, read-only), saved with the file's signature (`hashSignature`):
  a modified file is recomputed. `MediaMerger` merges a OneDrive item and a local file
  with the **same hash and same size** (never by name or date): the local file's media item
  receives the OneDrive source, its offline copy, its favorites, albums, cached files,
  downloads and playback position; it keeps the local file's metadata and date.
  If the contents later diverge (size, or different hashes), the OneDrive source goes back
  into its own media item; favorites and albums stay on the local file's one. No file is
  ever modified or deleted by deduplication.
- **OneDrive thumbnail cache**: capped at 256 MB and managed by Coil (LRU eviction), with no setting
  and no Storage page. Custom thumbnail sizes are documented by Microsoft but not
  yet verified on the device (automatic fallback to the standard sizes).
- **Very large images**: the viewer caps at around 24 MP. There is no tiled decoding,
  so no full resolution for panoramas or photos of 50 MP and more.
- **GIFs not animated** (no `coil-gif`). No specific support for Samsung motion photos /
  Motion Photos.
- **No video editing** (trimming). Image editing is limited to lossless rotation
  (JPEG/WebP) and cropping into a copy.
- **Photo picker URIs** (`content://media/picker/…`) and third-party FileProvider URIs: they
  are shown read-only, without being linked to the index (no favorite, no album).
- **Partial access**: only the selected items are visible, and folder albums are not
  cleaned up as long as access stays partial.
- **Trash**: the trash used is the Android trash (MediaStore, 30 days). Samsung
  Gallery's trash is neither read nor managed, and there is no "Trash" screen in the app yet.
- **One UI visual fidelity** is approximate until the Samsung reference screenshots have
  been compared (see `TODO.md`).
