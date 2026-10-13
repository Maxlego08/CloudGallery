package fr.eva.cloudgallery.ui.util

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import fr.eva.cloudgallery.MainActivity
import fr.eva.cloudgallery.R

/**
 * Opens the Android share sheet for `content://` URIs: MediaStore files, or
 * downloaded OneDrive files exposed by the app's FileProvider. Read-only
 * (FLAG_GRANT_READ_URI_PERMISSION and ClipData), never a write permission.
 */
fun shareMedia(context: Context, uris: List<Uri>, mimeTypes: List<String?>) {
    if (uris.isEmpty()) return
    context.startActivity(chooser(context, sendIntent(uris, mimeTypes), R.string.common_share))
}

/**
 * Share sheet for "Move to Secure Folder" ([title]: short instruction), to be launched
 * for a result: returning to the app triggers the "added successfully?" question.
 */
fun secureFolderShareIntent(context: Context, uris: List<Uri>, mimeTypes: List<String?>, @StringRes title: Int): Intent =
    chooser(context, sendIntent(uris, mimeTypes), title)

/** Read-only ACTION_SEND(_MULTIPLE) (FLAG_GRANT_READ_URI_PERMISSION and ClipData). */
private fun sendIntent(uris: List<Uri>, mimeTypes: List<String?>): Intent {
    require(uris.isNotEmpty())
    return if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }.apply {
        type = commonMimeType(mimeTypes)
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

// Common MIME type: jpeg + png -> "image/STAR"; image + video -> "STAR/STAR" (STAR = wildcard).
fun commonMimeType(mimeTypes: List<String?>): String {
    val known = mimeTypes.filterNotNull().filter { it.contains('/') }
    if (known.isEmpty()) return "*/*"
    if (known.distinct().size == 1) return known.first()
    val families = known.map { it.substringBefore('/') }.distinct()
    return if (families.size == 1) "${families.first()}/*" else "*/*"
}

/**
 * Opens the media in another application (external player for unsupported formats),
 * read-only. CloudGallery is excluded from the chooser: otherwise it would offer itself for
 * its own URIs (MainActivity's VIEW filter).
 */
fun openWithExternalApp(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser(context, intent, R.string.common_open_with))
}

/** App chooser without CloudGallery itself. */
private fun chooser(context: Context, target: Intent, @StringRes title: Int): Intent =
    Intent.createChooser(target, context.getString(title)).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
