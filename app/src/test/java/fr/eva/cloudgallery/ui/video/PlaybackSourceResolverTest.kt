package fr.eva.cloudgallery.ui.video

import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSourceInfo
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.OfflineFile
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.ui.video.PlaybackSourceResolver.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité des sources de lecture. android.net.Uri n'existe pas en test JVM : la branche
 * « fichier local » est vérifiée sur la fonction pure [PlaybackSourceResolver.choose].
 */
class PlaybackSourceResolverTest {

    private fun details(sources: List<MediaSourceInfo>, cloud: CloudThumbRef? = null) = MediaDetails(
        id = 1, type = MediaType.VIDEO, displayName = "a.mp4", mimeType = "video/mp4",
        dateTaken = 0, dateAdded = 0, dateModified = 0, width = 1920, height = 1080,
        durationMs = 1000, sizeBytes = 1, orientation = 0, uri = null, isFavorite = false,
        sources = sources, cloudThumb = cloud,
    )

    private fun oneDrive(itemId: String = "ITEM!1", cTag: String? = "ctag") = MediaSourceInfo(
        id = 1, kind = SourceKind.ONEDRIVE, externalId = itemId, uri = null, bucketKey = "onedrive:P",
        bucketName = "Photos", relativePath = "OneDrive/Photos/", sizeBytes = 1, dateModified = 0, cTag = cTag,
    )

    /** Source locale sans URI (cas dégradé) : jamais lue comme un fichier local. */
    private fun localWithoutUri() = MediaSourceInfo(
        id = 2, kind = SourceKind.LOCAL, externalId = "video:2", uri = null, bucketKey = "local:1",
        bucketName = "Camera", relativePath = "DCIM/Camera/", sizeBytes = 1, dateModified = 0,
    )

    @Test
    fun `local file always wins`() {
        assertEquals(Choice.LOCAL, PlaybackSourceResolver.choose(hasLocalFile = true, hasCloudItem = true, remoteAllowed = true))
        assertEquals(Choice.LOCAL, PlaybackSourceResolver.choose(hasLocalFile = true, hasCloudItem = true, remoteAllowed = false))
        assertEquals(Choice.LOCAL, PlaybackSourceResolver.choose(hasLocalFile = true, hasCloudItem = false, remoteAllowed = false))
    }

    @Test
    fun `cloud item is streamed only when access is allowed`() {
        assertEquals(Choice.REMOTE, PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = true, remoteAllowed = true))
        assertEquals(Choice.SIGNED_OUT, PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = true, remoteAllowed = false))
    }

    @Test
    fun `no source at all`() {
        assertEquals(Choice.NO_SOURCE, PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = false, remoteAllowed = true))
        assertEquals(
            PlaybackSource.Unavailable(UnavailableReason.NO_SOURCE),
            PlaybackSourceResolver.resolve(details(emptyList()), remoteAllowed = true),
        )
    }

    @Test
    fun `onedrive item gives a remote source with item id and ctag`() {
        val d = details(listOf(oneDrive()), CloudThumbRef("ITEM!1", "ctag"))
        assertTrue(d.hasCloud)
        assertFalse(d.hasLocal)
        assertEquals(PlaybackSource.Remote("ITEM!1", "ctag", "video/mp4"), PlaybackSourceResolver.resolve(d, remoteAllowed = true))
        assertTrue(PlaybackSourceResolver.needsRemote(d))
    }

    @Test
    fun `onedrive item without access is unavailable as signed out`() {
        val d = details(listOf(oneDrive()))
        assertEquals(
            PlaybackSource.Unavailable(UnavailableReason.SIGNED_OUT),
            PlaybackSourceResolver.resolve(d, remoteAllowed = false),
        )
    }

    @Test
    fun `local source without uri falls back to onedrive`() {
        val d = details(listOf(localWithoutUri(), oneDrive(cTag = null)))
        assertEquals(PlaybackSource.Remote("ITEM!1", null, "video/mp4"), PlaybackSourceResolver.resolve(d, remoteAllowed = true))
        assertFalse(PlaybackSourceResolver.needsRemote(details(listOf(localWithoutUri()))))
    }

    @Test
    fun `offline copy is played before streaming, local file before both`() {
        assertEquals(
            Choice.OFFLINE,
            PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = true, remoteAllowed = true, offlineFileOk = true),
        )
        // Copie conservée d'un élément supprimé de OneDrive : lue sans compte.
        assertEquals(
            Choice.OFFLINE,
            PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = false, remoteAllowed = false, offlineFileOk = true),
        )
        assertEquals(
            Choice.LOCAL,
            PlaybackSourceResolver.choose(hasLocalFile = true, hasCloudItem = true, remoteAllowed = true, offlineFileOk = true),
        )
    }

    @Test
    fun `damaged offline copy falls back to streaming`() {
        assertEquals(
            Choice.REMOTE,
            PlaybackSourceResolver.choose(hasLocalFile = false, hasCloudItem = true, remoteAllowed = true, offlineFileOk = false),
        )
    }

    @Test
    fun `offline copy resolves without needing onedrive access`() {
        val file = java.io.File.createTempFile("offline", ".mp4").apply {
            writeBytes(ByteArray(4))
            deleteOnExit()
        }
        val copy = OfflineFile(file, sizeBytes = 4, version = "ctag")
        val offlineSource = MediaSourceInfo(
            id = 3, kind = SourceKind.OFFLINE_COPY, externalId = "ITEM!1", uri = null, bucketKey = null,
            bucketName = null, relativePath = null, sizeBytes = 4, dateModified = 0, cTag = "ctag",
        )
        val d = details(listOf(oneDrive(), offlineSource)).copy(offlineFile = copy)
        assertTrue(PlaybackSourceResolver.isOfflineFileUsable(d.offlineCopy))
        assertFalse(PlaybackSourceResolver.needsRemote(d, offlineFileOk = true))
        assertEquals(PlaybackSource.Offline(copy, "video/mp4"), PlaybackSourceResolver.resolve(d, remoteAllowed = false, offlineFileOk = true))
        // Taille différente (copie tronquée) : inutilisable.
        assertFalse(PlaybackSourceResolver.isOfflineFileUsable(copy.copy(sizeBytes = 5)))
        assertFalse(PlaybackSourceResolver.isOfflineFileUsable(null))
    }
}
