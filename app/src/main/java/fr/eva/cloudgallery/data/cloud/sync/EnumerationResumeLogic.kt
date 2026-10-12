package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.data.cloud.graph.isTrustedGraphUrl

/*
 * Reprise d'une énumération complète OneDrive interrompue (logique pure, testée en JVM).
 *
 * Une énumération complète masque en fin de passage les éléments et dossiers qui n'ont pas été
 * vus. Pour la reprendre depuis la dernière page appliquée sans masquer à tort ceux des pages
 * précédentes, les opérations sur les ensembles « vus » sont journalisées page par page (un bloc
 * par page, terminé par une ligne de fin), puis rejouées à la reprise. Ordre des écritures pour
 * chaque page : transaction Room de la page, puis bloc du journal (synchronisé sur disque), puis
 * nextLink et nombre de pages. Une page rejouée après un arrêt est idempotente (index et
 * ensembles).
 */

/**
 * Âge maximal d'une énumération reprise : au-delà, ses liens de suite (nextLink, qui portent un
 * jeton Graph) risquent d'avoir expiré et l'index d'avoir trop changé ; elle repart du début.
 */
internal const val ENUMERATION_RESUME_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Journal plus gros que cela (≈ plusieurs millions d'éléments) : illisible, reprise abandonnée. */
internal const val SEEN_JOURNAL_MAX_BYTES = 64L * 1024 * 1024

/** Opération sur les ensembles « vus » d'une énumération complète. */
internal enum class SeenOp(val code: Char) {
    /** Média reçu. */
    MEDIA('M'),

    /** Dossier reçu. */
    FOLDER('F'),

    /** Elément signalé supprimé : retiré des deux ensembles. */
    REMOVE('R'),
}

internal data class SeenEntry(val op: SeenOp, val id: String)

private const val BLOCK_END = 'E'
private const val SEPARATOR = '\t'

/** Identifiant OneDrive journalisable tel quel (une ligne par opération). */
internal fun isJournalSafeId(id: String): Boolean = id.isNotEmpty() && id.none { it == '\n' || it == '\r' || it == SEPARATOR }

/** Bloc du journal pour une page : une ligne par opération, puis la ligne de fin. */
internal fun encodeSeenBlock(entries: List<SeenEntry>): String = buildString {
    for (entry in entries) {
        require(isJournalSafeId(entry.id)) { "Identifiant non journalisable" }
        append(entry.op.code).append(SEPARATOR).append(entry.id).append('\n')
    }
    append(BLOCK_END).append('\n')
}

/** Ensembles reconstruits d'après les blocs complets du journal. */
internal class SeenReplay(val seen: Set<String>, val seenFolders: Set<String>, val completeBlocks: Int)

/**
 * Rejoue le journal : seuls les blocs complets comptent (un bloc interrompu par un arrêt est
 * ignoré : sa page sera rejouée). Null si une ligne d'un bloc complet est illisible.
 */
internal fun replaySeenJournal(text: String): SeenReplay? {
    val seen = HashSet<String>()
    val seenFolders = HashSet<String>()
    var blocks = 0
    val pending = ArrayList<SeenEntry>()
    // Dernière ligne sans saut de ligne : écriture interrompue, ignorée.
    val lines = text.split('\n').dropLast(1)
    for (line in lines) {
        if (line.length == 1 && line[0] == BLOCK_END) {
            for (entry in pending) {
                when (entry.op) {
                    SeenOp.MEDIA -> seen += entry.id
                    SeenOp.FOLDER -> seenFolders += entry.id
                    SeenOp.REMOVE -> {
                        seen -= entry.id
                        seenFolders -= entry.id
                    }
                }
            }
            pending.clear()
            blocks++
            continue
        }
        if (line.length < 3 || line[1] != SEPARATOR) return null
        val op = SeenOp.entries.firstOrNull { it.code == line[0] } ?: return null
        val id = line.substring(2)
        if (!isJournalSafeId(id)) return null
        pending += SeenEntry(op, id)
    }
    return SeenReplay(seen, seenFolders, blocks)
}

/**
 * Etat de reprise enregistré : énumération liée à [version] (`drive|select`), commencée à
 * [startedAt], dont [pages] pages sont appliquées et journalisées, à poursuivre par [nextLink].
 */
internal data class EnumerationResumeMeta(
    val version: String?,
    val startedAt: Long,
    val nextLink: String?,
    val pages: Int,
)

/**
 * La reprise est possible : même lecteur et même liste de champs, énumération récente (horloge
 * non reculée), lien de suite Microsoft Graph, au moins une page appliquée et toutes ses pages
 * présentes dans le journal (un bloc de plus est permis : page appliquée mais lien pas encore
 * enregistré, rejouée sans dommage).
 */
internal fun canResumeEnumeration(
    meta: EnumerationResumeMeta,
    currentVersion: String,
    now: Long,
    journal: SeenReplay?,
    maxAgeMs: Long = ENUMERATION_RESUME_MAX_AGE_MS,
): Boolean {
    val link = meta.nextLink ?: return false
    return meta.version == currentVersion &&
        meta.startedAt > 0L && now >= meta.startedAt && now - meta.startedAt <= maxAgeMs &&
        meta.pages > 0 &&
        isTrustedGraphUrl(link) &&
        journal != null && journal.completeBlocks >= meta.pages
}

/**
 * Lien de suite repris refusé par Graph (requête invalide, lien expiré ou inconnu) : la reprise
 * est abandonnée et l'énumération repart du début, une seule fois.
 */
internal fun isRejectedResumeLink(status: Int): Boolean = status in 400..499 && status != 401 && status != 429
