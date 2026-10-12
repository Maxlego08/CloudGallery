package fr.eva.cloudgallery.ui.securefolder

/*
 * Logique pure de « Déplacer vers le Dossier sécurisé » (testée en JVM). Le Dossier sécurisé
 * Samsung n'a pas d'API publique : les fichiers du téléphone sont envoyés par la feuille de
 * partage Android à une appli du Dossier sécurisé, puis, seulement si l'utilisateur confirme que
 * l'ajout a réussi, les originaux vont à la corbeille Android (confirmée par le système). Un
 * élément uniquement sur OneDrive n'est jamais concerné (OneDrive en lecture seule).
 */

/**
 * Éléments envoyés en une fois : un seul partage, puis une vérification par l'utilisateur dans le
 * Dossier sécurisé avant la corbeille. Au-delà, la vérification devient peu réaliste.
 */
internal const val MAX_SECURE_FOLDER_ITEMS = 100

/** Un média de la sélection, réduit à ce qui décide de son envoi. */
internal data class SecureFolderCandidate(
    val id: Long,
    /** Fichier du téléphone avec une URI MediaStore. */
    val hasLocalFile: Boolean,
    /** Également sur OneDrive (média regroupé) : la copie OneDrive reste dans l'appli. */
    val hasCloud: Boolean,
)

/** Résultat de l'analyse d'une sélection. */
internal sealed interface SecureFolderPlan {
    /**
     * Envoi possible : [ids] dans l'ordre de la sélection ; [alsoOnOneDrive] parmi eux restent
     * visibles via OneDrive ; [skippedCloudOnly] éléments uniquement sur OneDrive ignorés.
     */
    data class Ready(val ids: List<Long>, val alsoOnOneDrive: Int, val skippedCloudOnly: Int) : SecureFolderPlan

    /** Aucun fichier du téléphone dans la sélection. */
    data object NothingLocal : SecureFolderPlan

    /** Trop de fichiers du téléphone pour un seul envoi ([max] au plus). */
    data class TooMany(val max: Int) : SecureFolderPlan
}

internal fun planSecureFolderMove(
    candidates: List<SecureFolderCandidate>,
    max: Int = MAX_SECURE_FOLDER_ITEMS,
): SecureFolderPlan {
    val local = candidates.filter { it.hasLocalFile }
    return when {
        local.isEmpty() -> SecureFolderPlan.NothingLocal
        local.size > max -> SecureFolderPlan.TooMany(max)
        else -> SecureFolderPlan.Ready(
            ids = local.map { it.id },
            alsoOnOneDrive = local.count { it.hasCloud },
            skippedCloudOnly = candidates.size - local.size,
        )
    }
}

/** L'action est proposée : appli Dossier sécurisé installée et au moins un fichier du téléphone. */
internal fun secureFolderActionVisible(secureFolderInstalled: Boolean, localCount: Int): Boolean =
    secureFolderInstalled && localCount > 0

/**
 * Étapes du déplacement. INTRO : explications avant la feuille de partage ; SHARING : feuille
 * de partage ouverte, en attente du retour dans l'appli ; CONFIRM_TRASH : l'utilisateur dit si
 * l'ajout a réussi, et seulement alors les originaux vont à la corbeille.
 */
enum class SecureFolderStep { INTRO, SHARING, CONFIRM_TRASH }

/** Étape suivante au retour de la feuille de partage (ignoré si elle n'avait pas été ouverte). */
internal fun stepAfterShareReturn(current: SecureFolderStep?): SecureFolderStep? =
    if (current == SecureFolderStep.SHARING) SecureFolderStep.CONFIRM_TRASH else current
