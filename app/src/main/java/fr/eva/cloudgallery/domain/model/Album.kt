package fr.eva.cloudgallery.domain.model

import android.net.Uri

enum class AlbumType {
    /** Dossier MediaStore (bucket) du téléphone. */
    LOCAL_FOLDER,

    /** Dossier OneDrive contenant des médias. */
    CLOUD_FOLDER,

    /** Album personnalisé : références vers des médias, sans copie de fichier. */
    VIRTUAL,
}

enum class AlbumSort { CUSTOM, NAME, RECENT, COUNT }

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
    /** Miniature OneDrive de la couverture, utilisée quand la couverture n'a pas de fichier local. */
    val coverCloud: CloudThumbRef? = null,
)
