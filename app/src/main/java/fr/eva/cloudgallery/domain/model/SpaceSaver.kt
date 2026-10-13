package fr.eva.cloudgallery.domain.model

/**
 * Local copy that can be deleted without loss: the same content (identical QuickXorHash and
 * size) is on OneDrive. [sourceId]: LOCAL source concerned; [uri]: MediaStore URI sent to the
 * Android trash.
 */
data class SpaceSaverCandidate(
    val sourceId: Long,
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val isVideo: Boolean,
    val displayName: String,
    val dateTaken: Long,
)

/**
 * Copies offered by the space saver. [excludedUserData]: copies verified but left out because
 * their media item (favorite or album member) is not yet grouped with the OneDrive item:
 * deleting them would lose that favorite or that album.
 */
data class SpaceSaverList(
    val candidates: List<SpaceSaverCandidate>,
    val excludedUserData: Int,
)

/** Age filter of the space saver (date taken). */
enum class SaverAge(val minAgeDays: Int?) {
    ALL(null),
    DAYS_30(30),
    DAYS_90(90),
    YEAR(365),
}
