package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.data.cloud.graph.DeletedFacetDto
import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.cloud.graph.FileFacetDto
import fr.eva.cloudgallery.data.cloud.graph.FileSystemInfoDto
import fr.eva.cloudgallery.data.cloud.graph.FolderFacetDto
import fr.eva.cloudgallery.data.cloud.graph.HashesDto
import fr.eva.cloudgallery.data.cloud.graph.ImageFacetDto
import fr.eva.cloudgallery.data.cloud.graph.ItemReferenceDto
import fr.eva.cloudgallery.data.cloud.graph.PhotoFacetDto
import fr.eva.cloudgallery.data.cloud.graph.VideoFacetDto
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.domain.model.MediaType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OneDriveLogicTest {

    private val now = 1_800_000_000_000L
    private val empty = JsonObject(emptyMap())

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun file(
        id: String = "F1",
        mime: String? = "image/jpeg",
        parent: String? = "P1",
        image: ImageFacetDto? = null,
        photo: PhotoFacetDto? = null,
        video: VideoFacetDto? = null,
        hashes: HashesDto? = null,
    ) = DriveItemDto(
        id = id,
        name = "$id.jpg",
        size = 1234,
        eTag = "e-$id",
        cTag = "c-$id",
        createdDateTime = "2024-06-01T10:00:00Z",
        lastModifiedDateTime = "2024-06-02T10:00:00Z",
        file = FileFacetDto(mimeType = mime, hashes = hashes),
        image = image,
        photo = photo,
        video = video,
        parentReference = parent?.let { ItemReferenceDto(id = it) },
    )

    // --- classifyDeltaItem ---------------------------------------------------------------

    @Test
    fun classify_deletedWinsOverEverything() {
        val item = file().copy(deleted = DeletedFacetDto())
        assertEquals(DeltaAction.Remove("F1"), classifyDeltaItem(item))
        val deletedFolder = DriveItemDto(id = "D1", deleted = DeletedFacetDto(), folder = FolderFacetDto())
        assertEquals(DeltaAction.Remove("D1"), classifyDeltaItem(deletedFolder))
    }

    @Test
    fun classify_rootIsFolderWithoutParent() {
        val root = DriveItemDto(id = "ROOT", name = "root", root = empty, folder = FolderFacetDto(3), parentReference = ItemReferenceDto(id = "X"))
        assertEquals(DeltaAction.UpsertFolder(CloudFolderFields("ROOT", null, "root", isRoot = true)), classifyDeltaItem(root))
    }

    @Test
    fun classify_packageAndRemoteItemAreIgnored() {
        assertEquals(DeltaAction.Remove("NB"), classifyDeltaItem(DriveItemDto(id = "NB", pkg = empty)))
        assertEquals(DeltaAction.Remove("R"), classifyDeltaItem(DriveItemDto(id = "R", remoteItem = empty, folder = FolderFacetDto())))
    }

    @Test
    fun classify_folder() {
        val folder = DriveItemDto(id = "D", name = "Photos", folder = FolderFacetDto(), parentReference = ItemReferenceDto(id = "ROOT"))
        assertEquals(DeltaAction.UpsertFolder(CloudFolderFields("D", "ROOT", "Photos", isRoot = false)), classifyDeltaItem(folder))
    }

    @Test
    fun classify_noFileFacetOrNonMediaIsRemoved() {
        assertEquals(DeltaAction.Remove("X"), classifyDeltaItem(DriveItemDto(id = "X", name = "x")))
        assertEquals(DeltaAction.Remove("F1"), classifyDeltaItem(file(mime = "application/pdf")))
        assertEquals(DeltaAction.Remove("F1"), classifyDeltaItem(file(mime = "image/svg+xml")))
        // Generic type without a media facet: ignored.
        assertEquals(DeltaAction.Remove("F1"), classifyDeltaItem(file(mime = "application/octet-stream")))
    }

    @Test
    fun classify_mediaTypes() {
        assertEquals(MediaType.IMAGE, (classifyDeltaItem(file(mime = "IMAGE/HEIC")) as DeltaAction.UpsertMedia).media.type)
        assertEquals(MediaType.VIDEO, (classifyDeltaItem(file(mime = "video/mp4")) as DeltaAction.UpsertMedia).media.type)
        val octetImage = file(mime = "application/octet-stream", image = ImageFacetDto(10, 10))
        assertEquals(MediaType.IMAGE, (classifyDeltaItem(octetImage) as DeltaAction.UpsertMedia).media.type)
        val noMimeVideo = file(mime = null, video = VideoFacetDto(1_000, 10, 10))
        assertEquals(MediaType.VIDEO, (classifyDeltaItem(noMimeVideo) as DeltaAction.UpsertMedia).media.type)
        val noMimePhoto = file(mime = "", photo = PhotoFacetDto("2020-01-01T00:00:00Z"))
        assertEquals(MediaType.IMAGE, (classifyDeltaItem(noMimePhoto) as DeltaAction.UpsertMedia).media.type)
    }

    @Test
    fun resolveCloudMediaType_rules() {
        assertEquals(MediaType.IMAGE, resolveCloudMediaType("image/png", false, false, false))
        assertEquals(MediaType.VIDEO, resolveCloudMediaType(" Video/QuickTime ", false, false, false))
        assertNull(resolveCloudMediaType("image/svg+xml", true, false, false))
        assertNull(resolveCloudMediaType("text/plain", true, true, true))
        assertNull(resolveCloudMediaType(null, false, false, false))
        assertEquals(MediaType.VIDEO, resolveCloudMediaType(null, true, true, true))
    }

    // --- Dates ---------------------------------------------------------------------------

    @Test
    fun chooseDateTaken_priorityOrder() {
        val photo = "2024-05-29T07:00:00Z"
        val fs = "2024-05-30T08:00:00Z"
        val created = "2024-06-01T10:00:00Z"
        assertEquals(ms(photo), chooseDateTakenMs(photo, fs, created))
        assertEquals(ms(fs), chooseDateTakenMs(null, fs, created))
        assertEquals(ms(fs), chooseDateTakenMs("not a date", fs, created))
        assertEquals(ms(created), chooseDateTakenMs("0001-01-01T00:00:00Z", null, created))
        assertEquals(0L, chooseDateTakenMs(null, "", null))
    }

    @Test
    fun parseGraphInstant_handlesOffsetsFractionsAndSentinel() {
        assertEquals(ms("2024-06-02T10:00:00.123Z"), parseGraphInstantMs("2024-06-02T10:00:00.123Z"))
        assertEquals(ms("2024-06-02T08:00:00Z"), parseGraphInstantMs("2024-06-02T10:00:00+02:00"))
        assertNull(parseGraphInstantMs("0001-01-01T00:00:00Z"))
        assertNull(parseGraphInstantMs("1970-01-01T00:00:00Z"))
        assertNull(parseGraphInstantMs("2024-06-02"))
        assertNull(parseGraphInstantMs(null))
    }

    // --- Field mapping -------------------------------------------------------------------

    @Test
    fun mediaFields_mapping() {
        val item = file(
            image = ImageFacetDto(4000, 3000),
            photo = PhotoFacetDto("2024-05-29T07:00:00Z"),
            hashes = HashesDto(quickXorHash = "QXH=", sha1Hash = "SHA"),
        ).copy(fileSystemInfo = FileSystemInfoDto("2024-05-30T08:00:00Z", "2024-05-31T08:00:00Z"))
        val m = toCloudMediaFields(item)!!
        assertEquals("F1", m.itemId)
        assertEquals("F1.jpg", m.name)
        assertEquals(ms("2024-05-29T07:00:00Z"), m.dateTakenMs)
        assertEquals(ms("2024-06-01T10:00:00Z"), m.dateAddedMs)
        assertEquals(ms("2024-05-31T08:00:00Z"), m.dateModifiedMs)
        assertEquals(4000, m.width)
        assertEquals(3000, m.height)
        assertEquals(0L, m.durationMs)
        assertEquals(1234L, m.sizeBytes)
        assertEquals("onedrive:P1", m.bucketKey)
        assertEquals("P1", m.parentId)
        assertEquals("qxh:QXH=", m.contentHash)
        assertEquals("SHA", m.sha1Hash)
        assertEquals("c-F1", m.cTag)
        assertEquals("e-F1", m.eTag)
    }

    @Test
    fun mediaFields_videoDimensionsAndDuration_nullParent() {
        val m = toCloudMediaFields(file(mime = "video/mp4", parent = null, video = VideoFacetDto(65_000, 1920, 1080)))!!
        assertEquals(1920, m.width)
        assertEquals(1080, m.height)
        assertEquals(65_000L, m.durationMs)
        assertNull(m.bucketKey)
        assertNull(m.parentId)
        assertNull(m.contentHash)
        // No capture date from the photo facet or the client: service creation date.
        assertEquals(ms("2024-06-01T10:00:00Z"), m.dateTakenMs)
    }

    @Test
    fun mediaFields_missingDimensionsAreZero() {
        val m = toCloudMediaFields(file(image = ImageFacetDto(null, null)))!!
        assertEquals(0, m.width)
        assertEquals(0, m.height)
    }

    @Test
    fun bucketKeys_roundTrip() {
        assertEquals("onedrive:ABC", cloudBucketKey("ABC"))
        assertNull(cloudBucketKey(null))
        assertNull(cloudBucketKey(" "))
        assertEquals("ABC", folderIdOfCloudBucket("onedrive:ABC"))
        assertNull(folderIdOfCloudBucket("local:12"))
        assertNull(folderIdOfCloudBucket("onedrive:"))
    }

    @Test
    fun lastOccurrenceById_keepsLatestStateInOrder() {
        val a1 = file(id = "A")
        val b = file(id = "B")
        val a2 = file(id = "A").copy(deleted = DeletedFacetDto())
        assertEquals(listOf(b, a2), lastOccurrenceById(listOf(a1, b, a2)))
        assertEquals(emptyList<DriveItemDto>(), lastOccurrenceById(emptyList()))
    }

    // --- Folders -------------------------------------------------------------------------

    private val folders = listOf(
        CloudFolderFields("ROOT", null, "root", isRoot = true),
        CloudFolderFields("PH", "ROOT", "Photos", isRoot = false),
        CloudFolderFields("Y24", "PH", "2024", isRoot = false),
        CloudFolderFields("ORPHAN", "UNKNOWN", "Perdu", isRoot = false),
        CloudFolderFields("C1", "C2", "Cycle1", isRoot = false),
        CloudFolderFields("C2", "C1", "Cycle2", isRoot = false),
    ).associateBy { it.itemId }

    @Test
    fun cloudFolderPath_variants() {
        assertEquals("OneDrive/", cloudFolderPath("ROOT", folders, "OneDrive"))
        assertEquals("OneDrive/Photos/2024/", cloudFolderPath("Y24", folders, "OneDrive"))
        assertEquals("Perdu/", cloudFolderPath("ORPHAN", folders, "OneDrive"))
        assertEquals("Cycle1/Cycle2/", cloudFolderPath("C2", folders, "OneDrive"))
        assertNull(cloudFolderPath("NOPE", folders, "OneDrive"))
    }

    @Test
    fun cloudFolderAlbumName_rules() {
        assertEquals("OneDrive", cloudFolderAlbumName(folders["ROOT"], "OneDrive", "Sans nom"))
        assertEquals("2024", cloudFolderAlbumName(folders["Y24"], "OneDrive", "Sans nom"))
        assertEquals("Sans nom", cloudFolderAlbumName(null, "OneDrive", "Sans nom"))
        assertEquals("Sans nom", cloudFolderAlbumName(CloudFolderFields("Z", "ROOT", " ", false), "OneDrive", "Sans nom"))
    }

    @Test
    fun descendantFolderIds_includesRootsAndAllDescendants() {
        assertEquals(setOf("PH", "Y24"), descendantFolderIds(setOf("PH"), folders.values))
        assertEquals(setOf("ROOT", "PH", "Y24"), descendantFolderIds(setOf("ROOT"), folders.values))
        assertEquals(setOf("C1", "C2"), descendantFolderIds(setOf("C1"), folders.values))
        assertEquals(emptySet<String>(), descendantFolderIds(emptySet(), folders.values))
    }

    // --- Removals ------------------------------------------------------------------------

    private fun stamp(id: Long, externalId: String, missingSince: Long? = null) =
        CloudStamp(sourceId = id, mediaId = id * 10, externalId = externalId, missingSince = missingSince)

    @Test
    fun planCloudRemovals_fullEnumerationHidesUnseen_neverDeletesImmediately() {
        val seen = stamp(1, "A")
        val unseen = stamp(2, "B")
        val plan = planCloudRemovals(listOf(seen, unseen), seen = setOf("A"), now = now)
        assertEquals(listOf(unseen), plan.hide)
        assertTrue(plan.purge.isEmpty())
    }

    @Test
    fun planCloudRemovals_incrementalNeverHidesFromAbsence() {
        val plan = planCloudRemovals(listOf(stamp(1, "A"), stamp(2, "B")), seen = null, now = now)
        assertTrue(plan.hide.isEmpty())
        assertTrue(plan.purge.isEmpty())
    }

    @Test
    fun planCloudRemovals_purgesOnlyAfterGracePeriod() {
        val recent = stamp(1, "A", missingSince = now - MISSING_GRACE_MS + 1)
        val expired = stamp(2, "B", missingSince = now - MISSING_GRACE_MS)
        val plan = planCloudRemovals(listOf(recent, expired), seen = null, now = now)
        assertEquals(listOf(expired), plan.purge)
        // Already hidden: neither re-hidden nor purged during the grace period, even if absent from a full enumeration.
        val full = planCloudRemovals(listOf(recent), seen = emptySet(), now = now)
        assertTrue(full.hide.isEmpty())
        assertTrue(full.purge.isEmpty())
    }

    @Test
    fun shouldRunForegroundSync_rules() {
        assertTrue(shouldRunForegroundSync(lastSyncAt = 0, retryNotBefore = 0, now = now))
        assertFalse(shouldRunForegroundSync(lastSyncAt = now - 60_000, retryNotBefore = 0, now = now))
        assertTrue(shouldRunForegroundSync(lastSyncAt = now - FOREGROUND_SYNC_MIN_INTERVAL_MS, retryNotBefore = 0, now = now))
        assertFalse(shouldRunForegroundSync(lastSyncAt = 0, retryNotBefore = now + 1, now = now))
        // Clock moved back: sync is not blocked.
        assertTrue(shouldRunForegroundSync(lastSyncAt = now + 3_600_000, retryNotBefore = 0, now = now))
    }

    // --- Sync start and drive binding ---------------------------------------------------

    @Test
    fun cursorVersion_roundTrip() {
        assertEquals("b!drive|s1", cloudCursorVersion("b!drive", "s1"))
        assertEquals(CloudCursorBinding("b!drive", "s1"), parseCloudCursorVersion("b!drive|s1"))
        assertEquals(CloudCursorBinding("legacy", null), parseCloudCursorVersion("legacy"))
        assertNull(parseCloudCursorVersion(null))
        assertNull(parseCloudCursorVersion(""))
        assertNull(parseCloudCursorVersion("|s1"))
    }

    @Test
    fun planSyncStart_firstRunOrUnboundCursorIsFullAndNeverPurges() {
        assertEquals(SyncStart.Full, planSyncStart(null, null, "D1", "s1", hasIndexedData = false))
        // First run interrupted before binding: the current drive is adopted.
        assertEquals(SyncStart.Full, planSyncStart(null, null, "D1", "s1", hasIndexedData = true))
    }

    @Test
    fun planSyncStart_sameDriveWithLinkIsIncremental() {
        val link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=abc"
        assertEquals(SyncStart.Incremental(link), planSyncStart(link, "D1|s1", "D1", "s1", hasIndexedData = true))
    }

    @Test
    fun planSyncStart_boundButNoLinkOrNewSelect_isFull() {
        // Interrupted full enumeration: it restarts from the beginning, on the same drive.
        assertEquals(SyncStart.Full, planSyncStart(null, "D1|s1", "D1", "s1", hasIndexedData = true))
        // $select changed: the saved deltaLink carries the old one, so a new full enumeration.
        assertEquals(SyncStart.Full, planSyncStart("https://graph.microsoft.com/x", "D1|s0", "D1", "s1", hasIndexedData = true))
        assertEquals(SyncStart.Full, planSyncStart("https://graph.microsoft.com/x", "D1", "D1", "s1", hasIndexedData = true))
    }

    @Test
    fun planSyncStart_otherDrive_requiresConfirmationWhenDataExists() {
        val link = "https://graph.microsoft.com/x"
        assertEquals(SyncStart.AccountMismatch, planSyncStart(link, "D1|s1", "D2", "s1", hasIndexedData = true))
        assertEquals(SyncStart.AccountMismatch, planSyncStart(null, "D1|s1", "D2", "s1", hasIndexedData = true))
        // No data to protect: the new drive is simply adopted.
        assertEquals(SyncStart.Full, planSyncStart(link, "D1|s1", "D2", "s1", hasIndexedData = false))
    }

    @Test
    fun planSyncStart_afterRestoredFolderDecision_isFullEvenIfRunWasInterrupted() {
        // Restored folder: the deltaLink is cleared along with the page. A run interrupted afterwards
        // resumes with a full enumeration, never from the old deltaLink.
        assertEquals(SyncStart.Full, planSyncStart(null, "D1|s1", "D1", "s1", hasIndexedData = true))
    }

    @Test
    fun deltaPageStep_restoredFolderRestartsAtOnce() {
        val next = "https://graph.microsoft.com/next"
        val delta = "https://graph.microsoft.com/delta"
        // Incremental run: the full restart waits for neither the following pages nor the deltaLink.
        assertEquals(DeltaPageStep.RestartFull, deltaPageStep(full = false, restoredFolderWithHiddenItems = true, next, null))
        assertEquals(DeltaPageStep.RestartFull, deltaPageStep(full = false, restoredFolderWithHiddenItems = true, null, delta))
        // Already in a full enumeration: the flow continues normally.
        assertEquals(DeltaPageStep.NextPage(next), deltaPageStep(full = true, restoredFolderWithHiddenItems = true, next, null))
        assertEquals(DeltaPageStep.Finish(delta), deltaPageStep(full = true, restoredFolderWithHiddenItems = true, null, delta))
    }

    @Test
    fun deltaPageStep_followsNextLinkThenFinishes() {
        val next = "https://graph.microsoft.com/next"
        val delta = "https://graph.microsoft.com/delta"
        assertEquals(DeltaPageStep.NextPage(next), deltaPageStep(full = false, restoredFolderWithHiddenItems = false, next, null))
        assertEquals(DeltaPageStep.Finish(delta), deltaPageStep(full = false, restoredFolderWithHiddenItems = false, null, delta))
        assertEquals(DeltaPageStep.MissingLink, deltaPageStep(full = false, restoredFolderWithHiddenItems = false, null, null))
        assertEquals(DeltaPageStep.MissingLink, deltaPageStep(full = false, restoredFolderWithHiddenItems = false, " ", ""))
    }

    @Test
    fun restoredRetryNotBefore_keepsPendingPauseOnly() {
        // Graph pause still running after a restart: honoured.
        assertEquals(now + 30 * 60_000L, restoredRetryNotBefore(now + 30 * 60_000L, now))
        // Pause elapsed or absent.
        assertEquals(0L, restoredRetryNotBefore(now - 1, now))
        assertEquals(0L, restoredRetryNotBefore(now, now))
        assertEquals(0L, restoredRetryNotBefore(0L, now))
        // Abnormally distant value (clock moved back): ignored.
        assertEquals(0L, restoredRetryNotBefore(now + MAX_STORED_THROTTLE_MS + 1, now))
        assertEquals(now + MAX_STORED_THROTTLE_MS, restoredRetryNotBefore(now + MAX_STORED_THROTTLE_MS, now))
    }

    @Test
    fun accountAllowed_onlyForTheVerifiedAccount_andNeverAfterAMismatch() {
        // Index bound to account A by the last run.
        assertTrue(cloudAccountAllowed("A", verifiedAccountId = "A", mismatch = false))
        // Another account signed in: blocked until a run verifies its drive.
        assertFalse(cloudAccountAllowed("B", verifiedAccountId = "A", mismatch = false))
        assertFalse(cloudAccountAllowed(null, verifiedAccountId = "A", mismatch = false))
        // No verified account (empty index, or data from before verification): allowed.
        assertTrue(cloudAccountAllowed("B", verifiedAccountId = null, mismatch = false))
        // Other drive detected: blocked even for the verified account, until the purge.
        assertFalse(cloudAccountAllowed("A", verifiedAccountId = "A", mismatch = true))
        assertFalse(cloudAccountAllowed("B", verifiedAccountId = null, mismatch = true))
    }

    // --- Folder tombstones ----------------------------------------------------------------

    /** ROOT > P > C > G ; ROOT > Q. */
    private val tree = listOf(
        CloudFolderFields("ROOT", null, "root", isRoot = true),
        CloudFolderFields("P", "ROOT", "Parent", isRoot = false),
        CloudFolderFields("C", "P", "Enfant", isRoot = false),
        CloudFolderFields("G", "C", "Petit-enfant", isRoot = false),
        CloudFolderFields("Q", "ROOT", "Autre", isRoot = false),
    )

    @Test
    fun tombstones_goneFolderWithHiddenItemsInSubtree_isMarkedWithItsAncestors() {
        val plan = planFolderTombstones(tree, markers = emptySet(), gone = setOf("P", "C", "G"), keepVisible = emptySet(), hiddenFolders = setOf("G"))
        // P and C have no items but link G to the restored folder.
        assertEquals(setOf("P", "C", "G"), plan.mark)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun tombstones_goneFolderWithoutHiddenItems_isDeleted() {
        val plan = planFolderTombstones(tree, markers = emptySet(), gone = setOf("Q"), keepVisible = emptySet(), hiddenFolders = setOf("G"))
        assertTrue(plan.mark.isEmpty())
        assertEquals(setOf("Q"), plan.delete)
    }

    @Test
    fun tombstones_existingMarkers_areDeletedOnceNothingHiddenRemains_andNeverRemarked() {
        val kept = planFolderTombstones(tree, markers = setOf("P", "C", "G"), gone = emptySet(), keepVisible = emptySet(), hiddenFolders = setOf("C"))
        // P and C stay (already marked: nothing to re-mark); G has nothing left under it.
        assertTrue(kept.mark.isEmpty())
        assertEquals(setOf("G"), kept.delete)

        val purged = planFolderTombstones(tree, markers = setOf("P", "C", "G"), gone = emptySet(), keepVisible = emptySet(), hiddenFolders = emptySet())
        assertEquals(setOf("P", "C", "G"), purged.delete)
    }

    @Test
    fun tombstones_goneFolderStillHoldingVisibleItems_isLeftUntouched() {
        val plan = planFolderTombstones(tree, markers = emptySet(), gone = setOf("Q"), keepVisible = setOf("Q"), hiddenFolders = setOf("Q"))
        assertTrue(plan.mark.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun subtreeHasHiddenItems_followsDescendantsOnly() {
        assertTrue(subtreeHasHiddenItems(setOf("P"), tree, hiddenFolders = setOf("G")))
        assertTrue(subtreeHasHiddenItems(setOf("G"), tree, hiddenFolders = setOf("G")))
        // A hidden sibling or ancestor does not concern this folder.
        assertFalse(subtreeHasHiddenItems(setOf("Q"), tree, hiddenFolders = setOf("G")))
        assertFalse(subtreeHasHiddenItems(setOf("C"), tree, hiddenFolders = setOf("P")))
        assertFalse(subtreeHasHiddenItems(emptySet(), tree, hiddenFolders = setOf("G")))
    }

    @Test
    fun subtreeHasHiddenItems_toleratesParentCycles() {
        val cyclic = listOf(CloudFolderFields("A", "B", "a", isRoot = false), CloudFolderFields("B", "A", "b", isRoot = false))
        assertTrue(subtreeHasHiddenItems(setOf("A"), cyclic, hiddenFolders = setOf("B")))
        assertFalse(subtreeHasHiddenItems(setOf("A"), cyclic, hiddenFolders = setOf("Z")))
    }
}
