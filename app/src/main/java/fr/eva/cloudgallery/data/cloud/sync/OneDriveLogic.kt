package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.domain.model.MediaType
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * Logique pure (sans dépendance Android) de la synchronisation OneDrive, testée en JVM.
 * Lecture seule : rien ici ne modifie OneDrive ni les fichiers de l'appareil.
 */

/** Fournisseur OneDrive dans la table sync_cursor (deltaLink Microsoft Graph). */
internal const val ONEDRIVE_PROVIDER = "onedrive"

/**
 * Champs demandés à la requête delta. Le `$select` est recopié dans le deltaLink : toute
 * modification doit s'accompagner d'un nouveau [DELTA_SELECT_VERSION] (énumération complète).
 */
internal const val DELTA_SELECT =
    "id,name,size,eTag,cTag,createdDateTime,lastModifiedDateTime,fileSystemInfo,file,folder,package," +
        "image,photo,video,parentReference,deleted,root,remoteItem"

/** Version de [DELTA_SELECT] enregistrée avec le curseur. */
internal const val DELTA_SELECT_VERSION = "s1"

/** Intervalle minimal entre deux synchronisations déclenchées par le retour au premier plan. */
internal const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 5 * 60_000L

private const val CLOUD_BUCKET_PREFIX = "onedrive:"

/** Profondeur maximale d'un chemin de dossiers (protection contre un cycle de parents). */
private const val MAX_FOLDER_DEPTH = 64

/** Clé de dossier d'une source OneDrive : « onedrive:<id du dossier parent> ». */
internal fun cloudBucketKey(parentId: String?): String? =
    parentId?.takeIf { it.isNotBlank() }?.let { CLOUD_BUCKET_PREFIX + it }

/** Inverse de [cloudBucketKey]. */
internal fun folderIdOfCloudBucket(bucketKey: String): String? =
    bucketKey.takeIf { it.startsWith(CLOUD_BUCKET_PREFIX) }?.substring(CLOUD_BUCKET_PREFIX.length)?.takeIf { it.isNotEmpty() }

/**
 * Date ISO 8601 de Graph en ms. Null si absente, illisible ou non positive (Graph renvoie
 * parfois la valeur sentinelle 0001-01-01).
 */
internal fun parseGraphInstantMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli().takeIf { it > 0 }
    } catch (e: DateTimeParseException) {
        null
    } catch (e: ArithmeticException) {
        null
    }
}

/** Date de prise de vue : photo.takenDateTime, puis création côté client, puis création côté service ; 0 sinon. */
internal fun chooseDateTakenMs(photoTaken: String?, fileSystemCreated: String?, created: String?): Long =
    parseGraphInstantMs(photoTaken) ?: parseGraphInstantMs(fileSystemCreated) ?: parseGraphInstantMs(created) ?: 0L

/**
 * Type de média d'un fichier OneDrive. Le type MIME décide (SVG exclu) ; s'il est absent ou
 * générique, les facettes vidéo / image / photo servent de repli. Null : pas un média.
 */
internal fun resolveCloudMediaType(mimeType: String?, hasImage: Boolean, hasPhoto: Boolean, hasVideo: Boolean): MediaType? {
    val mime = mimeType?.trim()?.lowercase().orEmpty()
    return when {
        mime == "image/svg+xml" -> null
        mime.startsWith("image/") -> MediaType.IMAGE
        mime.startsWith("video/") -> MediaType.VIDEO
        mime.isEmpty() || mime == "application/octet-stream" -> when {
            hasVideo -> MediaType.VIDEO
            hasImage || hasPhoto -> MediaType.IMAGE
            else -> null
        }
        else -> null
    }
}

/** Champs d'un média OneDrive (un élément = un média, jamais fusionné avec un fichier local). */
internal data class CloudMediaFields(
    val itemId: String,
    val type: MediaType,
    val name: String,
    val mimeType: String?,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val bucketKey: String?,
    val parentId: String?,
    val eTag: String?,
    val cTag: String?,
    val quickXorHash: String?,
    val sha1Hash: String?,
) {
    /** Empreinte de contenu (dédoublonnage futur) : QuickXorHash, seul hachage garanti sur OneDrive. */
    val contentHash: String? get() = quickXorHash?.let { "qxh:$it" }
}

/** Dossier OneDrive. */
internal data class CloudFolderFields(
    val itemId: String,
    val parentId: String?,
    val name: String,
    val isRoot: Boolean,
)

/** Effet d'un élément de la requête delta sur l'index local. */
internal sealed interface DeltaAction {
    data class UpsertMedia(val media: CloudMediaFields) : DeltaAction
    data class UpsertFolder(val folder: CloudFolderFields) : DeltaAction

    /**
     * Elément supprimé, déplacé hors du lecteur, devenu non média, ou ignoré (paquet OneNote,
     * élément partagé d'un autre lecteur). Dossier ou fichier : décidé par l'index local, la
     * facette `folder` n'étant pas toujours présente sur un élément supprimé.
     */
    data class Remove(val itemId: String) : DeltaAction
}

/** Classe un élément de la requête delta (règles appliquées dans cet ordre). */
internal fun classifyDeltaItem(item: DriveItemDto): DeltaAction = when {
    item.deleted != null -> DeltaAction.Remove(item.id)
    item.root != null -> DeltaAction.UpsertFolder(
        CloudFolderFields(itemId = item.id, parentId = null, name = item.name.orEmpty(), isRoot = true),
    )
    item.remoteItem != null || item.pkg != null -> DeltaAction.Remove(item.id)
    item.folder != null -> DeltaAction.UpsertFolder(
        CloudFolderFields(itemId = item.id, parentId = item.parentReference?.id, name = item.name.orEmpty(), isRoot = false),
    )
    item.file == null -> DeltaAction.Remove(item.id)
    else -> toCloudMediaFields(item)?.let { DeltaAction.UpsertMedia(it) } ?: DeltaAction.Remove(item.id)
}

/** Champs d'un fichier média, ou null si le fichier n'est ni une image ni une vidéo. */
internal fun toCloudMediaFields(item: DriveItemDto): CloudMediaFields? {
    val file = item.file ?: return null
    val type = resolveCloudMediaType(file.mimeType, item.image != null, item.photo != null, item.video != null) ?: return null
    val created = parseGraphInstantMs(item.createdDateTime)
    val modified = parseGraphInstantMs(item.fileSystemInfo?.lastModifiedDateTime)
        ?: parseGraphInstantMs(item.lastModifiedDateTime)
        ?: created
        ?: 0L
    val taken = chooseDateTakenMs(item.photo?.takenDateTime, item.fileSystemInfo?.createdDateTime, item.createdDateTime)
    val image = item.image?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
    val video = item.video?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
    val parentId = item.parentReference?.id?.takeIf { it.isNotBlank() }
    return CloudMediaFields(
        itemId = item.id,
        type = type,
        name = item.name?.takeIf { it.isNotBlank() } ?: item.id,
        mimeType = file.mimeType,
        dateTakenMs = if (taken > 0) taken else modified,
        dateAddedMs = created ?: modified,
        dateModifiedMs = modified,
        width = image?.width ?: video?.width ?: 0,
        height = image?.height ?: video?.height ?: 0,
        durationMs = if (type == MediaType.VIDEO) (item.video?.duration ?: 0L).coerceAtLeast(0L) else 0L,
        sizeBytes = (item.size ?: 0L).coerceAtLeast(0L),
        bucketKey = cloudBucketKey(parentId),
        parentId = parentId,
        eTag = item.eTag,
        cTag = item.cTag,
        quickXorHash = file.hashes?.quickXorHash?.takeIf { it.isNotBlank() },
        sha1Hash = file.hashes?.sha1Hash?.takeIf { it.isNotBlank() },
    )
}

/**
 * Le même élément peut apparaître plusieurs fois dans un flux delta : seule sa dernière
 * occurrence compte. Ordre conservé (celui des dernières occurrences).
 */
internal fun lastOccurrenceById(items: List<DriveItemDto>): List<DriveItemDto> {
    val latest = LinkedHashMap<String, DriveItemDto>(items.size)
    for (item in items) {
        latest.remove(item.id)
        latest[item.id] = item
    }
    return latest.values.toList()
}

/** Nom d'album d'un dossier OneDrive : [rootLabel] pour la racine, [unnamedLabel] sans nom connu. */
internal fun cloudFolderAlbumName(folder: CloudFolderFields?, rootLabel: String, unnamedLabel: String): String = when {
    folder == null -> unnamedLabel
    folder.isRoot -> rootLabel
    else -> folder.name.takeIf { it.isNotBlank() } ?: unnamedLabel
}

/**
 * Chemin lisible d'un dossier (« OneDrive/Photos/2024/ »), la racine étant [rootLabel]. Un parent
 * inconnu tronque le chemin (sans préfixe racine) ; un cycle est interrompu. Null si le dossier
 * est inconnu.
 */
internal fun cloudFolderPath(folderId: String, folders: Map<String, CloudFolderFields>, rootLabel: String): String? {
    var current = folders[folderId] ?: return null
    val names = ArrayList<String>()
    val visited = HashSet<String>()
    var reachedRoot = false
    while (names.size < MAX_FOLDER_DEPTH && visited.add(current.itemId)) {
        if (current.isRoot) {
            reachedRoot = true
            break
        }
        names += current.name
        current = current.parentId?.let { folders[it] } ?: break
    }
    val parts = if (reachedRoot) listOf(rootLabel) + names.asReversed() else names.asReversed()
    return parts.joinToString(separator = "/", postfix = "/")
}

/** Dossiers [roots] et tous leurs descendants connus. */
internal fun descendantFolderIds(roots: Set<String>, folders: Collection<CloudFolderFields>): Set<String> {
    if (roots.isEmpty()) return emptySet()
    val children = HashMap<String, MutableList<String>>()
    for (f in folders) f.parentId?.let { children.getOrPut(it) { ArrayList() }.add(f.itemId) }
    val result = LinkedHashSet<String>(roots)
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        for (child in children[queue.removeFirst()].orEmpty()) {
            if (result.add(child)) queue.addLast(child)
        }
    }
    return result
}

/** Source OneDrive de l'index (pour le plan de retrait). */
internal data class CloudStamp(
    val sourceId: Long,
    val mediaId: Long,
    val externalId: String,
    val missingSince: Long?,
)

internal data class CloudRemovalPlan(
    /** Sources à masquer (conservées avec favoris et albums). */
    val hide: List<CloudStamp>,
    /** Sources masquées depuis plus de [MISSING_GRACE_MS] : retirées de l'index. */
    val purge: List<CloudStamp>,
)

/**
 * Fin de synchronisation. [seen] (éléments médias reçus) n'est fourni qu'après une énumération
 * complète : une source visible absente est alors masquée. Une source n'est jamais retirée
 * immédiatement : seule une source masquée depuis plus de [MISSING_GRACE_MS] est purgée.
 */
internal fun planCloudRemovals(all: List<CloudStamp>, seen: Set<String>?, now: Long): CloudRemovalPlan {
    val hide = ArrayList<CloudStamp>()
    val purge = ArrayList<CloudStamp>()
    for (stamp in all) {
        val since = stamp.missingSince
        when {
            since != null -> if (now - since >= MISSING_GRACE_MS) purge += stamp
            seen != null && stamp.externalId !in seen -> hide += stamp
        }
    }
    return CloudRemovalPlan(hide, purge)
}

/**
 * Synchro au retour au premier plan : pas pendant une limitation Graph, ni moins de
 * [minIntervalMs] après la précédente (une horloge reculée n'empêche pas la synchro).
 */
internal fun shouldRunForegroundSync(
    lastSyncAt: Long,
    retryNotBefore: Long,
    now: Long,
    minIntervalMs: Long = FOREGROUND_SYNC_MIN_INTERVAL_MS,
): Boolean = now >= retryNotBefore && (lastSyncAt <= 0L || now < lastSyncAt || now - lastSyncAt >= minIntervalMs)

/** Pause Graph enregistrée plus lointaine que cela : horloge déréglée, valeur ignorée. */
internal const val MAX_STORED_THROTTLE_MS = 24 * 60 * 60_000L

/**
 * Pause de synchronisation relue au démarrage (Retry-After enregistré) : conservée si elle n'est
 * pas encore écoulée, ignorée (0) si elle est passée ou anormalement lointaine (horloge reculée).
 */
internal fun restoredRetryNotBefore(stored: Long, now: Long, maxMs: Long = MAX_STORED_THROTTLE_MS): Long =
    if (stored > now && stored - now <= maxMs) stored else 0L

/** Suite d'un passage après l'application d'une page delta. */
internal sealed interface DeltaPageStep {
    /**
     * Un dossier contenant des éléments masqués est réapparu (passage incrémental) : le deltaLink
     * est effacé aussitôt (un passage interrompu reprendra par une énumération complète) et
     * l'énumération complète démarre sans attendre la fin du flux delta.
     */
    data object RestartFull : DeltaPageStep

    data class NextPage(val url: String) : DeltaPageStep

    /** Dernière page : fin de passage puis enregistrement du deltaLink. */
    data class Finish(val deltaLink: String) : DeltaPageStep

    /** Page sans lien de suite : réponse invalide, le curseur n'est pas modifié. */
    data object MissingLink : DeltaPageStep
}

internal fun deltaPageStep(
    full: Boolean,
    restoredFolderWithHiddenItems: Boolean,
    nextLink: String?,
    deltaLink: String?,
): DeltaPageStep = when {
    !full && restoredFolderWithHiddenItems -> DeltaPageStep.RestartFull
    !nextLink.isNullOrBlank() -> DeltaPageStep.NextPage(nextLink)
    !deltaLink.isNullOrBlank() -> DeltaPageStep.Finish(deltaLink)
    else -> DeltaPageStep.MissingLink
}

/** Valeur de `sync_cursor.version` : lecteur lié et version du `$select`. */
internal fun cloudCursorVersion(driveId: String, selectVersion: String): String = "$driveId|$selectVersion"

internal data class CloudCursorBinding(val driveId: String, val selectVersion: String?)

/** Inverse de [cloudCursorVersion] (une ancienne valeur sans version de `$select` est acceptée). */
internal fun parseCloudCursorVersion(version: String?): CloudCursorBinding? {
    if (version.isNullOrBlank()) return null
    val sep = version.lastIndexOf('|')
    return if (sep < 0) {
        CloudCursorBinding(version, null)
    } else {
        version.substring(0, sep).takeIf { it.isNotBlank() }
            ?.let { CloudCursorBinding(it, version.substring(sep + 1).takeIf { v -> v.isNotBlank() }) }
    }
}

/** Point de départ d'une synchronisation. */
internal sealed interface SyncStart {
    /** Reprise depuis le deltaLink enregistré (changements seulement). */
    data class Incremental(val deltaLink: String) : SyncStart

    /** Enumération complète : premier passage, nouveau `$select`, deltaLink absent. */
    data object Full : SyncStart

    /**
     * Le lecteur connecté n'est pas celui des données de l'index : rien n'est synchronisé ni
     * supprimé sans la confirmation explicite de l'utilisateur.
     */
    data object AccountMismatch : SyncStart
}

/**
 * Choisit le départ d'une synchronisation d'après le curseur enregistré. Un curseur sans lecteur
 * (premier passage interrompu avant la liaison, ancienne version) adopte le lecteur courant ;
 * aucune donnée n'est jamais purgée ici.
 */
internal fun planSyncStart(
    storedLink: String?,
    storedVersion: String?,
    driveId: String,
    selectVersion: String,
    hasIndexedData: Boolean,
): SyncStart {
    val binding = parseCloudCursorVersion(storedVersion)
    return when {
        binding != null && binding.driveId != driveId -> if (hasIndexedData) SyncStart.AccountMismatch else SyncStart.Full
        binding == null -> SyncStart.Full
        binding.selectVersion != selectVersion -> SyncStart.Full
        storedLink.isNullOrBlank() -> SyncStart.Full
        else -> SyncStart.Incremental(storedLink)
    }
}
