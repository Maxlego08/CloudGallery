package fr.eva.cloudgallery.domain.model

import android.net.Uri

enum class AlbumType {
    /** MediaStore folder (bucket) on the phone. */
    LOCAL_FOLDER,

    /** OneDrive folder containing media. */
    CLOUD_FOLDER,

    /** Custom album: references to media, without copying any file. */
    VIRTUAL,
}

enum class AlbumSort { CUSTOM, NAME, RECENT, COUNT }

/** How an album is displayed, as chosen by the user ("Hide albums"). */
enum class AlbumVisibility {
    SHOWN,

    /** Missing from the Albums tab; its media remain in Photos, Videos and search. */
    HIDDEN_IN_ALBUMS,

    /** Missing from the Albums tab, and its media missing from Photos and Videos. */
    HIDDEN_EVERYWHERE,
}

data class AlbumSummary(
    val id: Long,
    val name: String,
    val type: AlbumType,
    val sourceKey: String?,
    val relativePath: String?,
    val mediaCount: Int,
    val coverMediaId: Long?,
    val coverUri: Uri?,
    val coverType: MediaType?,
    val coverDateModified: Long,
    val latestDate: Long,
    val isPinned: Boolean,
    /** OneDrive thumbnail of the cover, used when the cover has no local file. */
    val coverCloud: CloudThumbRef? = null,
    val visibility: AlbumVisibility = AlbumVisibility.SHOWN,
)
