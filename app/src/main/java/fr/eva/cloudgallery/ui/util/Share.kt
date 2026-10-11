package fr.eva.cloudgallery.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import fr.eva.cloudgallery.R

/** Ouvre la feuille de partage Android pour des URI `content://` locales. */
fun shareMedia(context: Context, uris: List<Uri>, mimeTypes: List<String?>) {
    if (uris.isEmpty()) return
    val mime = commonMimeType(mimeTypes)
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }.apply {
        type = mime
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
}

// Type MIME commun : jpeg + png -> "image/STAR" ; image + vidéo -> "STAR/STAR" (STAR = joker).
fun commonMimeType(mimeTypes: List<String?>): String {
    val known = mimeTypes.filterNotNull().filter { it.contains('/') }
    if (known.isEmpty()) return "*/*"
    if (known.distinct().size == 1) return known.first()
    val families = known.map { it.substringBefore('/') }.distinct()
    return if (families.size == 1) "${families.first()}/*" else "*/*"
}

/** Ouvre le média dans une autre application (lecteur externe en cas de format non pris en charge). */
fun openWithExternalApp(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_open_with)))
}
