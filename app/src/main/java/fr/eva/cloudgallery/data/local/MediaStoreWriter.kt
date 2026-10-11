package fr.eva.cloudgallery.data.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Écritures sur les fichiers locaux via MediaStore. Les opérations destructives passent
 * toujours par une demande système ([createDeleteRequest]) confirmée par l'utilisateur.
 */
@Singleton
class MediaStoreWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val indexer: LocalMediaIndexer,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : LocalMediaWriter {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun createDeleteRequest(uris: List<Uri>, toTrash: Boolean): IntentSender {
        val pending = if (toTrash) {
            MediaStore.createTrashRequest(resolver, uris, true)
        } else {
            MediaStore.createDeleteRequest(resolver, uris)
        }
        return pending.intentSender
    }

    override fun createWriteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    override fun canRotate(mimeType: String?): Boolean = mimeType?.lowercase() in EXIF_WRITABLE_MIME_TYPES

    override suspend fun rotateImage(uri: Uri, degrees: Int): Result<Unit> = withContext(io) {
        val result = catching {
            val angle = normalizeRightAngle(degrees)
                ?: throw IllegalArgumentException(context.getString(R.string.data_error_invalid_angle))
            if (angle == 0) return@catching
            val mime = resolver.getType(uri)?.lowercase()
            if (mime !in EXIF_WRITABLE_MIME_TYPES) {
                throw UnsupportedOperationException(context.getString(R.string.data_error_rotate_unsupported))
            }
            try {
                // "rw" et surtout pas "rwt" : le fichier doit rester lisible par ExifInterface.
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException(context.getString(R.string.data_error_save))
                pfd.use {
                    val exif = ExifInterface(it.fileDescriptor)
                    exif.rotate(angle)
                    exif.saveAttributes()
                }
            } catch (e: SecurityException) {
                throw SecurityException(context.getString(R.string.data_error_write_denied), e)
            }
        }
        if (result.isSuccess) requestSync()
        result
    }

    override suspend fun saveCroppedCopy(
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
    ): Result<Uri> = withContext(io) {
        val result = catching {
            val info = queryInfo(uri)
            if (info.mimeType?.startsWith("image/") != true) {
                throw UnsupportedOperationException(context.getString(R.string.data_error_not_image))
            }
            val angle = normalizeRightAngle(rotationDegrees)
                ?: throw IllegalArgumentException(context.getString(R.string.data_error_invalid_angle))

            // Décodage borné (l'orientation EXIF est appliquée par ImageDecoder).
            val decoded = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, imageInfo, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleSizeFor(imageInfo.size.width, imageInfo.size.height, MAX_DECODE_SIDE))
                }
            } catch (e: IOException) {
                throw IOException(context.getString(R.string.data_error_decode), e)
            }

            // Recadrage dans le repère source puis rotation : un seul bitmap intermédiaire.
            val rect = cropRectInSource(NormRect(left, top, right, bottom), angle)
            val bw = decoded.width
            val bh = decoded.height
            val x = (rect.left * bw).roundToInt().coerceIn(0, bw - 1)
            val y = (rect.top * bh).roundToInt().coerceIn(0, bh - 1)
            val w = (rect.width * bw).roundToInt().coerceIn(1, bw - x)
            val h = (rect.height * bh).roundToInt().coerceIn(1, bh - y)
            val matrix = Matrix().apply { if (angle != 0) postRotate(angle.toFloat()) }
            val output = Bitmap.createBitmap(decoded, x, y, w, h, matrix, true)
            if (output !== decoded) decoded.recycle()

            val name = croppedCopyName(info.displayName, System.currentTimeMillis())
            val newUri = insertPending(
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                displayName = name,
                mimeType = JPEG_MIME,
                relativePath = info.relativePath,
                fallbackRelativePath = DEFAULT_IMAGE_DIR,
                dateTaken = info.dateTaken,
            )
            try {
                val stream = resolver.openOutputStream(newUri)
                    ?: throw IOException(context.getString(R.string.data_error_save))
                stream.use { out ->
                    if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw IOException(context.getString(R.string.data_error_save))
                    }
                }
                output.recycle()
                copyExifDates(from = uri, to = newUri)
                publish(newUri)
            } catch (e: Exception) {
                runCatching { resolver.delete(newUri, null, null) }
                throw e
            }
            newUri
        }
        if (result.isSuccess) requestSync()
        result
    }

    override suspend fun copyToFolder(uris: List<Uri>, relativePath: String): Result<Int> = withContext(io) {
        val target = normalizeRelativePath(relativePath)
        var copied = 0
        var lastError: Exception? = null
        for (source in uris) {
            try {
                copyOne(source, target)
                copied++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Copie impossible : $source", e)
                lastError = e
            }
        }
        if (copied > 0) requestSync()
        if (copied == 0 && uris.isNotEmpty()) {
            Result.failure(lastError ?: IOException(context.getString(R.string.data_error_copy_failed)))
        } else {
            Result.success(copied)
        }
    }

    // ------------------------------------------------------------------------------------

    private fun copyOne(source: Uri, relativePath: String) {
        val info = queryInfo(source)
        val isVideo = info.mimeType?.startsWith("video/") == true
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val newUri = insertPending(
            collection = collection,
            displayName = info.displayName ?: source.lastPathSegment ?: "media",
            mimeType = info.mimeType,
            relativePath = relativePath,
            fallbackRelativePath = null,
            dateTaken = info.dateTaken,
        )
        try {
            val input = resolver.openInputStream(source) ?: throw IOException(context.getString(R.string.data_error_decode))
            input.use { ins ->
                val output = resolver.openOutputStream(newUri) ?: throw IOException(context.getString(R.string.data_error_save))
                output.use { outs -> ins.copyTo(outs, COPY_BUFFER) }
            }
            publish(newUri)
        } catch (e: Exception) {
            runCatching { resolver.delete(newUri, null, null) }
            throw e
        }
    }

    /** Crée une entrée MediaStore en IS_PENDING=1 (invisible des autres apps tant qu'elle n'est pas publiée). */
    private fun insertPending(
        collection: Uri,
        displayName: String,
        mimeType: String?,
        relativePath: String?,
        fallbackRelativePath: String?,
        dateTaken: Long?,
    ): Uri {
        fun values(path: String?) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            if (mimeType != null) put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (!path.isNullOrEmpty()) put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            if (dateTaken != null && dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val first = try {
            resolver.insert(collection, values(relativePath))
        } catch (e: IllegalArgumentException) {
            // Dossier non autorisé pour ce type de média (ex. Download/ pour une image).
            if (fallbackRelativePath == null) throw e
            null
        }
        return first
            ?: fallbackRelativePath?.let { resolver.insert(collection, values(it)) }
            ?: throw IOException(context.getString(R.string.data_error_save))
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    /** Reporte les dates de prise de vue et l'appareil (hors GPS) ; l'image produite est déjà droite. */
    private fun copyExifDates(from: Uri, to: Uri) {
        runCatching {
            val original = resolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
            val values = EXIF_TAGS_TO_COPY.mapNotNull { tag -> original.getAttribute(tag)?.let { tag to it } }
            if (values.isEmpty()) return
            resolver.openFileDescriptor(to, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                values.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
            }
        }.onFailure { Log.w(TAG, "Copie EXIF impossible", it) }
    }

    private data class SourceInfo(
        val displayName: String?,
        val mimeType: String?,
        val relativePath: String?,
        val dateTaken: Long?,
    )

    private fun queryInfo(uri: Uri): SourceInfo {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val fromStore = runCatching {
            resolver.query(uri, projection, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
                SourceInfo(
                    displayName = str(MediaStore.MediaColumns.DISPLAY_NAME),
                    mimeType = str(MediaStore.MediaColumns.MIME_TYPE),
                    relativePath = str(MediaStore.MediaColumns.RELATIVE_PATH),
                    dateTaken = str(MediaStore.MediaColumns.DATE_TAKEN)?.toLongOrNull(),
                )
            }
        }.getOrNull()
        return (fromStore ?: SourceInfo(null, null, null, null)).let {
            if (it.mimeType == null) it.copy(mimeType = resolver.getType(uri)) else it
        }
    }

    private fun requestSync() {
        appScope.launch(io) {
            runCatching { indexer.sync(full = false) }.onFailure { Log.w(TAG, "Synchronisation impossible", it) }
        }
    }

    /** Comme runCatching, mais sans avaler l'annulation de la coroutine. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Opération sur fichier en échec", e)
        Result.failure(e)
    }

    private companion object {
        const val TAG = "MediaStoreWriter"
        const val MAX_DECODE_SIDE = 8192
        const val JPEG_QUALITY = 95
        const val JPEG_MIME = "image/jpeg"
        const val DEFAULT_IMAGE_DIR = "Pictures/"
        const val COPY_BUFFER = 64 * 1024

        /** Formats dont l'orientation EXIF est modifiable sans réencodage. */
        val EXIF_WRITABLE_MIME_TYPES = setOf("image/jpeg", "image/jpg", "image/webp")

        val EXIF_TAGS_TO_COPY = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
    }
}
