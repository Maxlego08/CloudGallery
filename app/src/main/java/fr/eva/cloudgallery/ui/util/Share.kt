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
 * Ouvre la feuille de partage Android pour des URI `content://` : fichiers MediaStore, ou
 * fichiers OneDrive téléchargés exposés par le FileProvider de l'appli. Lecture seule
 * (FLAG_GRANT_READ_URI_PERMISSION et ClipData), jamais d'autorisation d'écriture.
 */
fun shareMedia(context: Context, uris: List<Uri>, mimeTypes: List<String?>) {
    if (uris.isEmpty()) return
    context.startActivity(chooser(context, sendIntent(uris, mimeTypes), R.string.common_share))
}

/**
 * Feuille de partage de « Déplacer vers le Dossier sécurisé » ([title] : consigne courte), à lancer
 * pour un résultat : le retour dans l'appli déclenche la question « ajout réussi ? ».
 */
fun secureFolderShareIntent(context: Context, uris: List<Uri>, mimeTypes: List<String?>, @StringRes title: Int): Intent =
    chooser(context, sendIntent(uris, mimeTypes), title)

/** ACTION_SEND(_MULTIPLE) en lecture seule (FLAG_GRANT_READ_URI_PERMISSION et ClipData). */
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

// Type MIME commun : jpeg + png -> "image/STAR" ; image + vidéo -> "STAR/STAR" (STAR = joker).
fun commonMimeType(mimeTypes: List<String?>): String {
    val known = mimeTypes.filterNotNull().filter { it.contains('/') }
    if (known.isEmpty()) return "*/*"
    if (known.distinct().size == 1) return known.first()
    val families = known.map { it.substringBefore('/') }.distinct()
    return if (families.size == 1) "${families.first()}/*" else "*/*"
}

/**
 * Ouvre le média dans une autre application (lecteur externe en cas de format non pris en charge),
 * en lecture seule. CloudGallery est exclue du choix : elle se proposerait sinon elle-même pour
 * ses propres URI (filtre VIEW de MainActivity).
 */
fun openWithExternalApp(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser(context, intent, R.string.common_open_with))
}

/** Sélecteur d'applis sans CloudGallery elle-même. */
private fun chooser(context: Context, target: Intent, @StringRes title: Int): Intent =
    Intent.createChooser(target, context.getString(title)).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
