package fr.eva.cloudgallery.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

/** EXIF metadata used by the Details sheet (raw values, formatted by [ExifReader.Companion]). */
data class ExifInfo(
    val make: String?,
    val model: String?,
    val focalLengthMm: Double?,
    val fNumber: Double?,
    val exposureSeconds: Double?,
    val iso: Int?,
    val flashFired: Boolean?,
    val latitude: Double?,
    val longitude: Double?,
) {
    val hasCameraInfo: Boolean
        get() = camera != null || focalLengthMm != null || fNumber != null || exposureSeconds != null || iso != null

    /** "Samsung Galaxy S22": the model alone if it already contains the make. */
    val camera: String?
        get() {
            val mk = make?.trim().orEmpty()
            val md = model?.trim().orEmpty()
            return when {
                mk.isEmpty() && md.isEmpty() -> null
                md.isEmpty() -> mk
                mk.isEmpty() || md.startsWith(mk, ignoreCase = true) -> md
                else -> "$mk $md"
            }
        }

    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/**
 * Reads EXIF off the main thread via androidx.exifinterface.
 * MediaStore only provides GPS coordinates if ACCESS_MEDIA_LOCATION is granted and the URI is
 * marked with [MediaStore.setRequireOriginal]; otherwise we silently fall back to the "redacted" URI.
 */
class ExifReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun read(uri: Uri): ExifInfo? = withContext(io) {
        val stream = openOriginal(uri) ?: openPlain(uri) ?: return@withContext null
        try {
            stream.use { parse(ExifInterface(it)) }
        } catch (_: Exception) {
            null
        }
    }

    private fun openOriginal(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (_: Exception) {
        // SecurityException without ACCESS_MEDIA_LOCATION, UnsupportedOperationException outside MediaStore…
        null
    }

    private fun openPlain(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    }

    private fun parse(exif: ExifInterface): ExifInfo {
        fun positive(tag: String): Double? = exif.getAttributeDouble(tag, -1.0).takeIf { it > 0.0 }
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1).takeIf { it > 0 }
        val flash = if (exif.getAttribute(ExifInterface.TAG_FLASH) != null) {
            (exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 0x1) == 1
        } else {
            null
        }
        val latLong = exif.latLong?.takeIf { it.size == 2 && !(it[0] == 0.0 && it[1] == 0.0) }
        return ExifInfo(
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            focalLengthMm = positive(ExifInterface.TAG_FOCAL_LENGTH),
            fNumber = positive(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = positive(ExifInterface.TAG_EXPOSURE_TIME),
            iso = iso,
            flashFired = flash,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
        )
    }

    companion object {
        /** "1/250 s" for short exposures, "2 s" / "1.5 s" for long exposures. */
        fun formatExposure(seconds: Double): String {
            if (seconds <= 0.0) return ""
            return if (seconds >= 1.0) {
                "${trimDecimal(seconds)} s"
            } else {
                "1/${(1.0 / seconds).roundToLong()} s"
            }
        }

        /** "f/1.8". */
        fun formatAperture(fNumber: Double): String = "f/" + String.format(Locale.ROOT, "%.1f", fNumber)

        /** "6 mm", "5.4 mm". */
        fun formatFocalLength(mm: Double): String = "${trimDecimal(mm)} mm"

        /** "48.85837, 2.29448". */
        fun formatCoordinates(latitude: Double, longitude: Double): String =
            String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

        /** At most one decimal, without a trailing ".0". */
        internal fun trimDecimal(value: Double): String {
            val rounded = Math.round(value * 10.0) / 10.0
            return if (abs(rounded - Math.rint(rounded)) < 1e-9) {
                rounded.toLong().toString()
            } else {
                String.format(Locale.ROOT, "%.1f", rounded)
            }
        }
    }
}
