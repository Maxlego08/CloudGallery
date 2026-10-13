# TODO — CloudGallery

What remains to be done after phases 1 to 3 (local MediaStore gallery, read-only OneDrive).
Checked boxes are already done. Rules that apply to every phase:

- no user file is deleted without explicit confirmation;
- no dummy feature (a missing button is better than a button that does nothing);
- no I/O on the main thread;
- only documented and verified APIs.

---

## Immediate next actions (before phase 3)

- [ ] Install on the Galaxy S22 and test on the real library:
      `D:\AndroidSDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`.
      Check permissions (full, partial, denied), first indexing, scrolling, grid zoom,
      selection, deletion to the trash, rotation, cropping, the video player and opening from
      another app.
- [ ] Run the on-device tests: `gradlew connectedDebugAndroidTest` (`MediaDaoTest`).
- [ ] Provide the Samsung Gallery screenshots listed in phase 7, then fix the visual differences.
- [x] Provide the Microsoft Entra **Client ID** (personal accounts) and the signature hash for
      the MSAL redirect URI: this is the prerequisite for phase 3 (in `local.properties`).

## Phase 3 — OneDrive: authentication, Graph, delta, thumbnails

### Authentication (MSAL)
- [x] Register the application in Microsoft Entra: personal accounts, Android platform,
      `msauth://fr.eva.cloudgallery/<hash>`, scopes `Files.Read`, `User.Read`, `offline_access`
      (procedure in the README).
- [x] Add the MSAL dependency (8.5.0, Duo SDK repository as `exclusiveContent` for `display-mask`)
      and declare it in `libs.versions.toml`.
- [x] Read the client ID and signature hash from `local.properties` (or a Gradle property),
      then expose them via `BuildConfig` and the manifest placeholder. Without valid values, the
      build succeeds and OneDrive is hidden.
- [x] Generate the MSAL configuration (`account_mode SINGLE`, audience `PersonalMicrosoftAccount`,
      file written at runtime in `noBackupFilesDir`) and declare `BrowserTabActivity` in the
      manifest.
- [x] Handle sign-in, sign-out and expiration (`acquireTokenSilent`, then "Sign-in required" on
      `MsalUiRequiredException`). "OneDrive account" screen in Menu and Settings, replacing
      "Available in a future version".
- [x] Decide what happens to OneDrive data on sign-out, with confirmation: purge the `ONEDRIVE`
      sources, the OneDrive albums and the cached thumbnails, without touching local files.
- [x] Exclude the MSAL token files from Android backups (`backup_rules.xml`,
      `data_extraction_rules.xml`).

### Microsoft Graph client
- [x] HTTP client (OkHttp 5.5.0, created on first call on the I/O thread), serialization with
      kotlinx-serialization, `Bearer` token sent only to `graph.microsoft.com`.
- [x] `GET /me/drive/root/delta` (the whole OneDrive) with an explicit `$select`. Follow
      `@odata.nextLink` and store `@odata.deltaLink` in `sync_cursor`
      (`provider = "onedrive"`), tied to the `drive.id` and to the `$select` version.
- [x] Handle `410 Gone` (expired deltaLink) with a full resync, using hide-rather-than-delete
      rules (`planCloudRemovals`).
- [x] Honor `429` / `503` + `Retry-After`, with capped exponential backoff, a global pause of
      all requests and no excessive parallelism.
- [x] Process `deleted` items: hide the source, then purge it after 30 days. The matching local
      file is never deleted. Deleted folders are processed at the end of the pass.
- [x] Filter out items without a `file` facet, and keep only image/video MIME types.
- [x] Write `ONEDRIVE` sources (`externalId`, `bucketKey = onedrive:<parentId>`, `eTag`,
      `cTag`, `quickXorHash`, `sha1Hash` if available), update `hasCloud` /
      `refreshSourceFlags` and create the OneDrive folder albums ("OneDrive album" badge).
- [x] Choose the date to display: `photo.takenDateTime`, then `fileSystemInfo.createdDateTime`,
      then `createdDateTime`.
- [x] Microsoft account change: sync suspended and data replaced only after confirmation.
- [x] "Show OneDrive items in Photos" setting (disabled by default) to avoid duplicates in
      Photos and Videos before deduplication.

### Thumbnails
- [x] Add a Coil `Fetcher`/`Keyer` for Graph thumbnails
      (`/items/{id}/thumbnails/0/{size}/content`), with a cache key based on `cTag`, custom
      `c{n}x{n}_crop` sizes (falling back to the standard sizes) and the ImageLoader's shared
      disk cache.
- [x] Show thumbnails in the grid, album covers, the filmstrip and the album picker, with a
      cloud source indicator on the cell only.
- [x] OneDrive preview in the viewer (no zoom, video badge), local-file actions hidden or
      disabled for items that exist only on OneDrive.
- [x] OneDrive sync state in the Menu and Settings.
- [x] Room migration 2→3 (`media.cloudItemId`, `media.cloudCTag`, `cloud_folder` table), schema
      exported, `MigrationTest` (2→3 and 1→3).

### On-device checks (phase 3)
Details in the README ("On-device checks: OneDrive"):
- [ ] Run `gradlew connectedDebugAndroidTest` (`MigrationTest`, `CloudSyncWriterTest`,
      `CloudDataPurgerTest`, `MediaDaoTest`): compiled, not yet run.
- [ ] Sign-in through the browser tab, account kept after a force stop, re-sign-in, sign-out.
      Check that `broker_redirect_uri_registered=false` keeps the browser tab even if
      Microsoft Authenticator is installed.
- [ ] Delete a test photo and folder on OneDrive (web): check that `deleted`, `root`,
      `parentReference.id`, `file.hashes.quickXorHash` and `photo` do come back with the
      `$select` (otherwise change `DELTA_SELECT` and increment `DELTA_SELECT_VERSION`), and that
      Graph accepts the literal `$select` with commas.
- [ ] Custom thumbnail sizes `c{n}x{n}_crop` and `c1600x1600` accepted as a path segment, and
      thumbnail redirects without authentication.
- [ ] Rename, move and restore a folder from the OneDrive recycle bin, including a folder whose
      photos are in a subfolder: check whether the delta request returns every restored
      descendant (if so, the broad "folder removed from the index" detection could be tightened
      to avoid needless full enumerations).
- [ ] Sign-in to another Microsoft account: "Different OneDrive account" card, nothing purged
      before confirmation.
- [ ] Logcat without token, deltaLink or account name; OkHttp 5.5.0 and TLS on the device.
- [ ] `adb shell run-as fr.eva.cloudgallery ls -R shared_prefs files app_fr.eva.cloudgallery`:
      MSAL file names (including `app_fr.eva.cloudgallery/adalks`) match the backup
      exclusions.
- [ ] Sign-out while thumbnails are loading on a slow network: the account stays "Not signed
      in" and no sync restarts.

## Phase 4 — Streaming, adaptive previews, offline downloads

- [x] Viewer: thumbnail then OneDrive preview in `CloudOnlyPage` (`ui/viewer/ViewerPages.kt`),
      done in phase 3.
- [x] Viewer: OneDrive original on deep zoom (on-demand download, automatic on an unmetered
      network, "Load original (x MB)" chip otherwise, "Load originals on mobile network"
      setting), `CloudOriginalStore` + `CdnDownloader`, zoomable preview.
- [x] Choose the preview tier from the screen size, the image and the network (tiers 1600 /
      2048, at most 1600 on a metered network, tier rejected by Graph dropped).
- [x] Add `PlaybackSource.Remote` (`ui/video/PlaybackSource.kt`): temporary
      `@microsoft.graph.downloadUrl` URL, `ResolvingDataSource` on top of `DefaultHttpDataSource`
      (HTTP Range for seeking) and URL renewal on expiry (`data/cloud/stream/`,
      `CloudDownloadUrlProvider`, shared access guard `CloudAccessGuard`, Media3 log scrubbed of
      URLs).
- [x] Play an `OFFLINE_COPY` first when one exists (`PlaybackSource.Offline`, copy checked off
      the main thread; "Open with" through the FileProvider).
- [x] Check that Media3 1.11.1 does return `ERROR_CODE_IO_*` for network errors (checked in the
      bytecode: 2001/2002 network, 2004 HTTP status, 2000 for a resolver error), and add the
      matching messages (network, link, sign-in required, throttling, not found).
- [x] Default network state (`data/net/NetworkStatus`): metered or not, data saver (counted as
      metered), unknown state treated as metered. Used by the previews and downloads of the
      following slices.
- [x] Share and "Open with" for a cloud media item: download of the original (cancellable
      progress, 20 items / 500 MB), `CloudGalleryFileProvider`, CloudGallery excluded from the
      chooser.
- [x] Database v4 (`cached_file.version`, `download_task.cloudItemId` / `cTag` / `attempts`), schema
      exported, `MigrationTest` 3→4, 2→4, 1→4; temporary cache of originals (1 GB, 7 days,
      cleanup at startup); originals purged on sign-out (`CloudDataGeneration`).
- [x] Message when the share caps are exceeded (`share_cloud_too_large`, `share_cloud_too_many`)
      offering "Keep offline"; offline copies shared without downloading.
- [ ] Read the EXIF of a downloaded original (Details panel) without going through `MediaStore.setRequireOriginal`.
- [x] Offline downloads: `download_task` queue (Range resume, progress, cancellation, pause
      after a stop by the user, coded errors), user-initiated JobScheduler job
      (`OfflineTransferJobService`), notification with "Cancel". A finished copy
      (`filesDir/offline/`, excluded from backups) becomes an `OFFLINE_COPY` source and sets
      `hasOffline`; registration re-checked (purge, release, version).
- [x] "Keep offline" / "Free up space" action (`pinned`) from the viewer and in multi-selection;
      notification permission on first use; large batch (200 items / 1 GB) confirmed;
      insufficient space (system screen); "Wi-Fi only" setting and "Download now"; "Offline"
      section of the Account screen.
- [x] New version of a kept item: new download, the old copy remains until replaced; stale
      temporary original removed.
- [x] Sign-out, purge of leftover data, account change: engine stopped, copies deleted,
      confirmation with the number of copies and of last copies (D3).
- [x] Item deleted from OneDrive (D1): copy hidden along with it, never purged without a decision
      after 30 days; Account screen card and Menu badge ("Delete the copies" /
      "Keep them"); kept copy visible, "Free up" with last-copy confirmation; item that comes
      back re-attached to the same media item.
- [x] Phase 4 review fixes: D1 decision limited to the confirmed list and re-checked in the
      transaction; last copies counted including hidden copies, and "Free up" abandoned if the
      count fails; purge confirmations disabled until the number of copies is read (cautious
      warning on failure); "Download now" limited to the tasks queued at that moment; "Wi-Fi
      only" setting applied right away (rescheduling, network checked before each item);
      `allocateBytes` called when free space is lacking but obtainable (copies and originals);
      pause at startup only after a stop during a download; end of work re-checked
      (`OfflineJobTracker`); "Free up" / "Cancel" of the current item without stopping the pass;
      content newer than the index waits for the sync; version re-requested from Graph before a
      Range resume; stopped update reported and resumable; original never overwritten before
      the next one is saved; conditional cleanup of originals; preview chosen once the network
      is known; high-resolution zoom with the file dimensions; cached preview only for an
      offline copy; deep zoom without an account reported; failed sign-out: queue restarted
      after the "signing out" state ends; tests `OfflineRepositoryTest`,
      `OfflineJobTrackerTest`, `CloudOriginalLogicTest`.

### On-device checks (phase 4)
Details in the README ("On-device checks: OneDrive streaming"):
- [x] `@microsoft.graph.downloadUrl` missing with `$select` on personal OneDrive: request without
      `$select` (full response), originals, video and downloads checked on the device.
- [ ] 206 response from the download host to Range requests (fast seek).
- [ ] HTTP status returned for an expired URL (401, 403, 404 or 410 expected) and actual
      validity period (URL reused for two minutes at most).
- [ ] Logcat without `files.1drv.com` or `tempauth` URLs, including after a playback error.
- [ ] Preview size `c2048x2048` accepted by Graph and image actually larger than `c1600x1600`
      (otherwise `CLOUD_PREVIEW_TIERS = [1600]`).
- [ ] 50 MP OneDrive original displayed without crashing (downsampled decoding); correct EXIF
      orientation.
- [ ] "Load original" chip on mobile data and with the data saver; automatic loading on Wi-Fi.
- [ ] Share an original to Gmail / Messages: OneDrive name shown; "Open with" without
      CloudGallery in the chooser.
- [ ] `connectedDebugAndroidTest`: `MigrationTest` (3→4, 2→4, 1→4), `CachedFileDaoTest`,
      `CloudDataPurgerTest`, `OfflineDaoTest`, `OfflineDownloadEngineTest`, `OfflineRepositoryTest`,
      `CloudSyncWriterTest`
      (the phase 3 instrumented suite has not run yet either).
- [ ] Offline copies (details in the README, "On-device checks: offline copies"):
  - [ ] first notification permission request, download also when denied (task visible in the
        Task manager);
  - [ ] Wi-Fi only: "Waiting for Wi-Fi" dialog, "Download now" on mobile, data saver included;
  - [ ] switch from Wi-Fi to mobile during a download: stop, resume when Wi-Fi returns (Range,
        partial file kept), never on mobile (`JobParameters.getNetwork`);
  - [ ] active VPN: the job starts;
  - [ ] stop from the Task manager: queue paused, no automatic resume (check whether
        `onStopJob` receives `STOP_REASON_USER` or the process is killed: exit reason
        `REASON_USER_REQUESTED` used at restart); app swiped away from recent apps with a queue
        waiting for Wi-Fi: queue still waiting when reopened;
  - [ ] "Download now" on mobile, then new version found by the sync: the new task waits for
        Wi-Fi;
  - [ ] "Wi-Fi only" enabled during a mobile download: stop and wait for Wi-Fi; disabled while
        the queue waits for Wi-Fi: starts right away;
  - [ ] "Free up" on the item being downloaded: the following ones continue;
  - [ ] storage almost full with other apps' cache: copy of several GB completed
        (`allocateBytes`);
  - [ ] phone reboot with a pending queue: resumed at the next opening;
  - [ ] One UI "sleeping apps" restrictions;
  - [ ] `parentReference.driveId` returned by Graph compared with the index drive (case
        ignored);
  - [ ] new version on OneDrive: re-download, old copy shown until then;
  - [ ] kept item deleted on OneDrive: hidden, card after 30 days (clock moved forward),
        "Keep them" / "Delete the copies";
  - [ ] sign-out: `files/offline` empty, message with the number of copies;
  - [ ] logcat without URL or file name; `dumpsys jobscheduler`: job 4001 "user-initiated".

## Phase 5 — LRU cache, Storage page, periodic sync

Decisions (phase 5): originals cap 1 GB by default (500 MB / 1 GB / 2 GB / 5 GB); Coil cache
(thumbnails and previews) separate from `cached_file`, with its own configurable cap; background
OneDrive sync configurable (off / 1 h / 6 h / 24 h, 6 h by default); in "OneDrive only" mode,
the Photos tab shows OneDrive regardless of the setting.

- [x] Inventory the cache files: originals and offline copies in `cached_file`
      (`lastAccessAt` refreshed on the I/O dispatcher); thumbnails and previews in the Coil disk
      cache, which has its own LRU eviction (decision: no row per thumbnail).
- [x] LRU eviction of originals under a configurable cap (`AppSettings.originalsCacheMaxBytes`),
      at startup and after each download (`CloudOriginalStore.requestTrim`). **`pinned` rows are
      never evicted or counted**; an original used less than 30 min ago (displayed, shared) is
      not evicted, so the cap may be exceeded for a while.
- [x] "Storage" page (Settings > Storage, `ui/storage/`): space measured on disk per tier
      (thumbnails and previews, originals, offline copies) and free space, caps for originals
      and thumbnails, "Clear cache" (thumbnails, previews, originals; never offline copies) with
      confirmation.
- [x] Coil: disk cache separate from `cached_file` (decision), configurable cap 100 / 250 / 500 MB /
      1 GB (250 MB by default, `ThumbnailCacheLimit`), applied at the next start.
- [x] Periodic WorkManager for the OneDrive delta (`OneDriveSyncWorker`, `@HiltWorker`):
      Settings > Account "Sync in the background" (off / 1 h / 6 h / 24 h, 6 h by default) and
      "Sync on Wi-Fi only"; battery not low; scheduled for a signed-in account, cancelled on
      sign-out (`BackgroundSyncScheduler`); default WorkManager initializer removed
      (`Configuration.Provider`).
- [ ] Background MediaStore catch-up (`ContentUriTrigger`): not done, the local index is already
      caught up at each opening (`MediaSyncCoordinator`); only useful for a future background
      feature that would read the local index.
- [x] Show the OneDrive sync state in Settings (last delta, errors, number of items): done in
      phase 3.
- [x] Full OneDrive enumeration resumable after a process kill or an error: nextLink and page
      count in `cloud_sync.xml`, seen items logged page by page
      (`noBackupFilesDir/onedrive_enumeration_seen.log`, `EnumerationResumeLogic`); resume
      refused (start from the beginning) if another drive, more than 24 h, incomplete log or
      link rejected.
- [x] "OneDrive only" usage (postponed from phase 4, decision D4): "Use OneDrive only" on the
      permission screen (choice remembered, forgotten as soon as access is granted); full tabs,
      OneDrive by default in Photos and Videos (`includeCloudOnlyInLibrary`,
      `MediaAccessState`), "Phone photos not allowed — Allow" banner, "Sign in to OneDrive"
      empty state without an account.
- [ ] Offline copies: automatically restart a new download detected by a background sync (today
      in the foreground only: the user-initiated job can only be scheduled while the app is
      visible); album covers and EXIF from the offline copy.
- [x] Deletion marker for OneDrive folders (database v5, `cloud_folder.deletedAt`): a deleted
      folder (and its intermediate folders) stays marked as long as its subtree has hidden
      items, then is removed after their purge (`planFolderTombstones`). Only a restored marker
      with hidden items triggers a full enumeration; a new folder no longer triggers one
      (except orphan hidden items from before v5, until their purge). The delta limited to the
      restored folder is not used (merging with the drive delta not verified).
- [x] Account change blocked from startup: verified MSAL account and "other account" saved
      (`CloudSyncThrottleStore`); `CloudAccessGuard` refuses any access to an unverified account
      or after another drive is detected, without waiting for the first pass, which then starts
      without delay. Data from before phase 5 (no verified account): access allowed until the
      next pass, which verifies.

### On-device checks (phase 5)
- [ ] Resumed enumeration: first sign-in on a large OneDrive, force stop after a few pages
      (logcat "Resuming the full enumeration after N pages" at restart); at the end, no item of
      the first pages hidden, same item count as an enumeration without a stop; logcat without
      nextLink.
- [ ] "OneDrive only": deny access, "Use OneDrive only"; without an account, Photos offers
      sign-in; signed in, Photos/Videos show OneDrive without the setting; "Allow" banner
      (request, then settings after a permanent denial); access granted then revoked in the
      settings: the permission screen comes back.
- [ ] Storage page: sizes consistent with Android's "App info > Storage"; "Clear cache" during
      an original download (download finishes normally); thumbnail cap changed: "applied at the
      next start", then effective after a force stop.
- [ ] Background sync: `adb shell dumpsys jobscheduler | grep -A5 onedrive` (or App
      Inspection > Background Task Inspector) shows the periodic work; disabled: cancelled;
      sign-out: cancelled; forced run (`adb shell cmd jobscheduler run -f fr.eva.cloudgallery
      <id>`) with the app closed: new items visible at opening without waiting.
- [ ] Other account: sign-out without purge is impossible, so test with "Leftover data" then
      sign-in to another account; force stop before the end of the first pass: at restart, no
      thumbnail of the old account requested, "Different OneDrive account" card.
- [ ] Deleted folders (OneDrive web): photo folder deleted then, before 30 days, new folder
      created (incremental sync, no full enumeration in the `OneDriveSync` logcat), then folder
      restored from the recycle bin (full enumeration, photos and album back).
- [ ] Originals cap at 500 MB with 1 GB of originals: the least recent go right away, except
      those opened less than 30 min ago; an original shared to Gmail stays readable.

## Phase 6 — Deduplication, space saver, search, mixed albums

Decisions (phase 6): hashes computed only while charging; space saver for photos and videos,
with an age filter; favorites not synced with Android.

- [x] QuickXorHash of local files (`data/dedup/QuickXorHash`, tested against an independent
      bit-by-bit implementation): `LocalHashWorker` while charging with battery not low,
      batches of 100 files / 1 GB (likely duplicates first: size present on OneDrive),
      8 min per pass then a chained pass, daily catch-up; saved with the file signature
      (`hashSignature`) only if the file did not change during the computation;
      `media.contentHash` = `qxh:<hash>`.
- [x] Local / OneDrive merge **only on identical QuickXorHash and size** (`MediaMerger`): the
      local file's media item receives the OneDrive source (and its offline copy); favorite,
      albums, album cover, cached files, downloads and playback position of the OneDrive media
      item are transferred to it. Identical duplicates: deterministic pairing by source order.
      No merge during a download in progress. Run after local indexing, each OneDrive pass and
      each batch of hashes.
- [x] Unmerge: different sizes, or two known different hashes (content modified on OneDrive, or
      local hash recomputed); a merely stale local hash (file touched, same size) keeps the
      merge until the next computation. The OneDrive source moves to a new media item with its
      offline copy, its original and its downloads; favorites and albums stay on the local
      file's media item.
- [x] Space saver (Settings > Storage > Space saver, `ui/saver/`): visible local copies whose
      hash is up to date (`hashSignature` = `"<sizeBytes>:<dateModified>:<generation>"`) and
      equal, size included, to that of a visible OneDrive item (grouped or not,
      `SPACE_SAVER_SELECT`); a copy that is a favorite or in an album is offered only once
      grouped with the OneDrive item. Age filter (30 days by default), everything checked then
      review, summary, in-app confirmation then system dialog, re-check in the database right
      before, **always the trash** (batches of 1,000), resync. Never triggered automatically.
- [x] Full-text search: `media_fts` index (FTS4 unicode61) on the name, the folders (visible
      sources, virtual albums, custom names) and the date and type labels (year, month and
      weekday in French and English, "video", "photo"), kept up to date by `SearchIndexer`
      (every write to media / media_source / album / album_media, incremental update). Search
      screen (icon in the Photos tab): suggestions (albums, clearable recent searches), results
      in a grid (visible media, OneDrive included), viewer within the results
      (`MediaContext.Search`). Location remains to be done along with geocoding.
- [x] Virtual albums mixing local and OneDrive sources: a merged media item stays in the album as
      long as one of its sources is visible, then is hidden (never deleted); tested
      (`MediaMergerTest`).
- [x] Folder albums: `ALBUM_MEMBERS` / `ALBUM_ROWS` (`FOLDER_MEMBER_SOURCE`) only count a hidden
      source if the media item has no visible source attached to a folder: a merged media item
      is only in the folders of its visible sources; a copy kept offline stays in its OneDrive
      folder as before.
- [x] OneDrive dates: no effect on matching, which is based only on hash and size; a merged
      media item keeps the local file's date (EXIF/MediaStore), never replaced by the OneDrive
      sync.
- [ ] Possibly sync favorites with `MediaStore.createFavoriteRequest` (with the system
      confirmation), provided it does not overwrite existing Samsung favorites.

### On-device checks (phase 6)
- [ ] Search: first launch after the update (logcat `SearchIndexer`: index filled, duration on
      the real library); "août 2026", a folder name, a renamed album, "video", a OneDrive-only
      item; new photo taken then searched for (index up to date a few seconds later); viewer
      opened from the results (swiping limited to the results); recent searches removed one by
      one and cleared; special inputs (`"`, `*`, `-`, `OR`) without error.
- [ ] Space saver: after the hashes are computed (while charging), a photo present on the phone
      and on OneDrive is offered, a photo modified on the phone no longer is; unchecking a copy
      keeps it; "Free up": app dialog then system dialog, files in the Samsung trash
      (restorable), still visible via OneDrive; system dialog declined: nothing is deleted;
      "more than a year" filter and large selection (more than 1,000: several system dialogs).
      `connectedDebugAndroidTest`: `SpaceSaverDaoTest`.
- [ ] Hashes: device plugged in, `adb shell dumpsys jobscheduler | grep -A5 local_hash` shows the
      work; logcat `MediaMerger` "N OneDrive item(s) attached"; a photo backed up to OneDrive
      appears only once in Photos with "Show OneDrive" enabled; unplugged during the
      computation: stops right away, resumes at the next charge.
- [ ] Local hash identical to Graph's for the same photo (compare `quickXorHash` of both sources
      in the database, `adb shell run-as … sqlite3`), photo and video of several GB.
- [ ] Unmerge: photo modified (cropped) on the phone then indexed and rehashed: two media
      items, favorite stays on the phone photo; new version on OneDrive: same.

## Navigation improvements (after phase 6)

- [x] Back: the screen leaves by sliding fully to the right (no fade), the previous screen comes
      back with a slight offset; follows the predictive back gesture. The viewer and the player
      keep their zoom-out.
- [x] Screen transitions matched to Samsung Gallery 15.8 (One UI 8, Android 16), measured frame
      by frame (`adb shell screenrecord`): top screen fully from the right, bottom screen offset
      by 30 % and dimmed almost to black (~130 ms), curve `cubic-bezier(0.9, 0, 0.3, 1)` over
      340 ms in both directions. Predictive back gesture: same transitions
      (`predictivePop*Transition`), linear to follow the finger; previously Navigation applied
      its own (screen shrunk to 70 %).
- [x] Fast scroll: handle on the right of the grid with a date bubble (month, year), to reach any
      date without scrolling through the whole grid. Photos and all collections
      (`ui/grid/FastScroller.kt`); position computed from the dates of all media in the context
      (`observe*Dates` queries); jump outside the loaded pages by reloading paging at the
      position of the period's first media item.
- [x] Caching of all of OneDrive: a button downloads the grid thumbnails and screen-size
      previews of all OneDrive items, kept outside the LRU cap, for smooth browsing (maximum
      zoom and videos stay online). Settings > Storage, estimate and confirmation, WorkManager as
      a `dataSync` foreground service, resume, "Keep up to date".
- [x] Samsung Secure Folder: "Move to Secure Folder" action for phone photos (Android share to a
      Secure Folder app, then originals moved to the trash after confirmation). No private Knox
      API. "More" menu of the selection and of the viewer, only if
      `com.samsung.knox.securefolder` is installed; at most 100 items per send; always the trash
      (`ui/securefolder/`).

### On-device checks (improvements)
- [ ] Back from Settings, Storage, an album, search: slide to the right, with the button and the
      gesture (predictive preview). Also check the dimming in light theme (not compared with Samsung).
- [ ] Secure Folder: the entry appears (selection with a phone photo, viewer) and not for a
      OneDrive-only item; a Secure Folder app (its Gallery) appears in the share menu, otherwise
      enable it in the Secure Folder settings and note the exact label of the setting; photo
      and video, several items (10, 100): do they all arrive in the Secure Folder?; on return,
      "Keep the originals": nothing is deleted; "Move the originals to the trash": system
      dialog, files in the Samsung trash (restorable); system dialog declined: nothing is
      deleted; share cancelled (back without choosing): the question appears, "Keep"; photo
      grouped with OneDrive: message shown, the item stays visible via OneDrive. Behavior on a
      device without a configured Secure Folder.
- [ ] Fast scroll: the handle appears while scrolling and disappears ~1.5 s later; drag from
      October 12, 2026 to January 2025: "January 2025" bubble (month zoom) or "Jan 15, 2025"
      (day zoom), light vibration at each month, grid positioned on the targeted header on
      release; then scroll back up to the top (previous pages loaded without a visible jump);
      position consistent with "Show OneDrive" enabled and disabled, in an album, favorites and
      a search; drag selection and pinch unchanged near the right edge; handle above the tab
      bar and the action bar in selection mode; date loading time on the real library.
      `connectedDebugAndroidTest`: `MediaDaoTest`
      (`dates_followGridOrderAndFilters`, `pagingTimeline_initialKey_startsAtRankAndPrependsBefore`).
- [ ] Caching of all of OneDrive: consistent estimate (number of OneDrive-only items), progress
      notification with "Cancel", progress in the Storage page; app closed during the pass: it
      continues; airplane mode then back: "Waiting" then resume without re-downloading (logcat
      `OneDrivePrecache`); afterwards, airplane mode: grid and viewer of OneDrive items shown
      without network; size shown in Storage; "Clear cache": everything deleted, "Keep up to
      date" unchecked.
- [ ] Caching: check that the grid at the default zoom and the viewer make no thumbnail request
      (OkHttp / Graph logcat) for an item already cached, and that the 2048 tier is accepted
      by Graph (otherwise previews saved at 1600).
- [ ] Caching: "Keep up to date" enabled, photo added to OneDrive from another device, sync: the
      photo is cached in the background; Android 15, pass longer than 6 h: interrupted by the
      system then resumed.

## Phase 7 — Visual fidelity, heavy images, polish

### Viewer and formats
- [ ] Tiled decoding (`BitmapRegionDecoder`) for images larger than 24 MP and panoramas
      (TODO in `ui/viewer/ZoomableImage.kt`).
- [ ] Animate GIFs with `io.coil-kt.coil3:coil-gif` (same version as Coil, 3.6.3), then animated
      WebP and HEIF if supported.
- [ ] Samsung Motion Photos: detection and playback of the video part, only after finding a
      documented format.
- [ ] Video editing (trimming): disabled for now (TODO in `ui/viewer/ViewerScreen.kt`).
      To be evaluated with Media3 Transformer, always saving a copy.
- [x] Grid ↔ viewer transition matched to Samsung Gallery (`ui/viewer/ViewerTransition.kt`):
      the photo grows from its thumbnail (cropped to a square) to its fitted size, and goes back
      on return (bars hidden and grid visible right away, thumbnail hidden during the flight).
      Grid thumbnail (memory cache) shown from the first frame; viewer pages composed after the
      opening (otherwise jank); opening started after the first frame is drawn. 250 ms,
      `cubic-bezier(0.6, 0.4, 0.45, 1)`. Without a visible thumbnail: fade.
- [x] Viewer return matched to Samsung (`cubic-bezier(0.15, 0.4, 0.1, 1)`, 280 ms: the photo
      loses half its size in ~50 ms), distinct from the opening.
- [x] "Show OneDrive items in Photos" enabled by default (duplicates merged in place).
- [x] Albums: "Hide albums" (Albums tab menu); per album: shown, hidden in Albums, or hidden in
      Albums and Photos (its media also leave Photos and Videos; `album.hideMedia`, database v7).
- [x] OneDrive album: "Open in OneDrive" (album menu) opens the folder on the web (Graph
      `webUrl`) in the browser. The OneDrive app does not open a folder from a link (personal
      account, tested: refused or app home screen).
- [ ] Transition: when returning on a photo other than the one opened, scroll the grid to its
      thumbnail (Samsung) instead of fading; start from the zoomed or dragged state without a
      jump.

### External URIs and partial access
- [ ] Photo picker URIs (`content://media/picker/...`) and third-party FileProvider URIs: they
      cannot be attached to a MediaStore source and are displayed without an index (TODO in
      `data/repository/MediaRepositoryImpl.kt`). Look into `MediaStore.getMediaUri` and the
      readable columns to attach some of them, otherwise keep the read-only mode.
- [ ] Partial access: explain in the UI that unselected items are hidden, not deleted. Offer
      full access without insisting. Test adding and removing items from the selection on
      One UI 8.
- [ ] Partial access: folder albums are not cleaned up. Check that empty folders do stay
      hidden.

### Samsung Gallery screenshots needed (One UI 8, Galaxy S22, light and dark themes)
They are used to compare margins, font sizes, colors, radii and animations pixel by pixel:
- [ ] Photos tab: grid at **every zoom level** (largest to smallest), day and month headers,
      tab bar, top bar collapsed and expanded, fast scroll (handle).
- [ ] Albums tab: album grid, pinned albums, sorting, album creation, album view.
- [ ] Viewer: UI (top/bottom bars, buttons), filmstrip, **details panel** (EXIF, map,
      location), viewer in landscape.
- [ ] Video player: controls (play, progress bar, ±10 s, speed, sound, rotation), locked
      state, landscape.
- [ ] Selection mode: header with counter, checkboxes, selecting a day, bottom action bar,
      "More" menu.
- [ ] Dialogs: deletion (trash / permanent), rename, album creation, copy to a folder, sorting,
      reindexing.
- [ ] Samsung Gallery settings (section structure and switch style).
- [ ] Menu tab / screen (Favorites, Videos, Trash, Settings...).

### Miscellaneous
- [ ] "Trash" screen (MediaStore `IS_TRASHED` items) with restore and permanent deletion, both
      confirmed by the system.
- [ ] Manual backup and restore of favorites and custom albums (JSON export), since the
      database is excluded from Android backups.
- [ ] Real release `signingConfig` if the app is to be distributed (and its signature hash
      added in Microsoft Entra).
- [ ] Merged manifest: the `NFC` permission and the activities added by MSAL's dependencies
      (YubiKey, Credentials); remove them with `tools:node="remove"` only after testing
      sign-in.
- [ ] Performance profile on 100,000 media: cold start, first indexing, fast scrolling,
      memory. Consider a Baseline Profile.
- [ ] Tests: MediaStore indexer on device (partial access, removable volume, version change),
      Compose UI tests of the main flows. (Room migrations are tested by `MigrationTest` since
      phase 3.)

---

## TODOs in the code

| File | Note |
|---|---|
| `app/src/main/java/fr/eva/cloudgallery/data/repository/MediaRepositoryImpl.kt` | Photo picker URIs (`content://media/picker/...`) and third-party FileProvider URIs: cannot be attached to a MediaStore source, the viewer displays them without an index |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ViewerScreen.kt` | `TODO(viewer)`: video editing (trimming) not supported |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ZoomableImage.kt` | `TODO(viewer)`: tiled decoding (`BitmapRegionDecoder`) for images larger than 24 MP |

## Known limitations (reminder)

- [ ] Read-only OneDrive: an item that exists only on OneDrive can be zoomed (original), played,
      shared and opened elsewhere, but not modified, deleted or copied to a phone folder, even
      when kept offline (app copy, read-only).
- [ ] Duplicates: a phone file and its OneDrive item only become a single media item once the
      local hash is computed (while charging); until then, two media items ("Show OneDrive items
      in Photos" setting disabled by default). A file modified after its backup is never
      merged.
- [ ] Without media permission, OneDrive can be browsed only after "Use OneDrive only"
      (phase 5); a build without OneDrive only shows the permission screen.
- [ ] No background sync: `work-runtime-ktx` and `hilt-work` are present but unused.
- [ ] Favorites and custom albums are specific to the app. They are lost on uninstall (database
      excluded from backups) and are not shared with Samsung Gallery.
- [ ] Unused `user_settings` table (settings live in DataStore): use it or drop it through a
      migration.
- [ ] No search by location or content, no animated GIFs, no Motion Photos, no full resolution
      beyond about 24 MP.
- [ ] One UI visual fidelity not verified against reference screenshots.
- [ ] Viewer: the date in the top bar ("Today", "Yesterday") is not recomputed at midnight while
      the viewer stays open.
- [ ] Grid: without paging placeholders, very fast scrolling pauses briefly when reaching the end
      of the loaded pages.
- [ ] Folder albums: no way to go back to the original name after a display rename (add
      "Restore folder name").
- [ ] Albums: no drag-and-drop reordering (`sortIndex` exists but cannot be changed from the
      UI).
- [ ] No Trash screen (see phase 7).
