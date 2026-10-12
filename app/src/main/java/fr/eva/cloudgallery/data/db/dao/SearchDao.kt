package fr.eva.cloudgallery.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import fr.eva.cloudgallery.data.db.entity.MediaRow
import kotlinx.coroutines.flow.Flow

/** Date de prise de vue en heure locale de l'appareil (`dateTaken` est en millisecondes). */
private const val LOCAL_DATE = "m.dateTaken / 1000, 'unixepoch', 'localtime'"

/**
 * Noms des dossiers d'un média pour l'index : dossiers de ses sources visibles, albums virtuels
 * qui le contiennent, noms personnalisés des albums-dossiers. Chaque sous-requête suit un index
 * (`media_source.mediaId`, `album_media.mediaId`, `album.sourceKey`).
 */
internal const val FTS_FOLDERS_SQL = """trim(
    COALESCE((SELECT group_concat(DISTINCT s.bucketName) FROM media_source s
        WHERE s.mediaId = m.id AND s.missingSince IS NULL AND s.bucketName IS NOT NULL), '') || ' ' ||
    COALESCE((SELECT group_concat(DISTINCT COALESCE(a.customName, a.name)) FROM album_media am
        JOIN album a ON a.id = am.albumId WHERE am.mediaId = m.id), '') || ' ' ||
    COALESCE((SELECT group_concat(DISTINCT a2.customName) FROM media_source s2
        JOIN album a2 ON a2.sourceKey = s2.bucketKey
        WHERE s2.mediaId = m.id AND s2.missingSince IS NULL AND a2.customName IS NOT NULL), ''))"""

/**
 * Libellés de date et de type d'un média : année, mois (français et anglais), jour du mois, jour
 * de la semaine, puis « vidéo video » ou « photo image ». Même ordre que MONTH_LABELS et
 * WEEKDAY_LABELS (SearchLogic.kt, vérifié par SearchLogicTest). Date inconnue : type seul.
 */
internal const val FTS_DATES_SQL = """trim(CASE WHEN m.dateTaken > 0 THEN
    strftime('%Y', $LOCAL_DATE) || ' ' ||
    CASE strftime('%m', $LOCAL_DATE)
        WHEN '01' THEN 'janvier january' WHEN '02' THEN 'février february' WHEN '03' THEN 'mars march'
        WHEN '04' THEN 'avril april' WHEN '05' THEN 'mai may' WHEN '06' THEN 'juin june'
        WHEN '07' THEN 'juillet july' WHEN '08' THEN 'août august' WHEN '09' THEN 'septembre september'
        WHEN '10' THEN 'octobre october' WHEN '11' THEN 'novembre november' WHEN '12' THEN 'décembre december'
        ELSE '' END || ' ' ||
    CAST(strftime('%d', $LOCAL_DATE) AS INTEGER) || ' ' ||
    CASE strftime('%w', $LOCAL_DATE)
        WHEN '0' THEN 'dimanche sunday' WHEN '1' THEN 'lundi monday' WHEN '2' THEN 'mardi tuesday'
        WHEN '3' THEN 'mercredi wednesday' WHEN '4' THEN 'jeudi thursday' WHEN '5' THEN 'vendredi friday'
        WHEN '6' THEN 'samedi saturday' ELSE '' END
    ELSE '' END || ' ' ||
    CASE m.mediaType WHEN 'VIDEO' THEN 'vidéo video' ELSE 'photo image' END)"""

/** Médias dont l'entrée d'index correspond à l'expression FTS [match] (voir ftsMatchExpression). */
private const val MATCHES = "m.id IN (SELECT rowid FROM media_fts WHERE media_fts MATCH :match)"

/**
 * Recherche plein texte sur `media_fts` et tenue à jour de cet index depuis la base.
 *
 * Résultats : médias visibles ([VISIBLE] : au moins une source présente), éléments uniquement sur
 * OneDrive compris quel que soit le réglage de Photos (ils sont aussi visibles dans leurs albums),
 * dans l'ordre des grilles.
 */
@Dao
interface SearchDao {

    // --- Résultats (grille, visionneuse, sélection) ---------------------------------------

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun pagingSearch(match: String): PagingSource<Int, MediaRow>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun observeSearchIds(match: String): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND $MATCHES")
    fun observeSearchCount(match: String): Flow<Int>

    /** Dates des résultats dans l'ordre de la grille (défilement rapide). */
    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun observeSearchDates(match: String): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES AND $IN_PERIOD $ORDER")
    suspend fun searchIdsInPeriod(match: String, startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES AND $BETWEEN $ORDER")
    suspend fun searchIdsBetween(match: String, newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    // --- Tenue à jour de l'index ------------------------------------------------------------

    /** Entrées de médias supprimés. */
    @Query("DELETE FROM media_fts WHERE rowid NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanEntries(): Int

    /** Entrées dont le nom, les dossiers ou les libellés de date ne sont plus à jour. */
    @Query(
        """DELETE FROM media_fts WHERE rowid IN (
            SELECT m.id FROM media m JOIN media_fts f ON f.rowid = m.id
            WHERE f.name != m.displayName OR f.folders != $FTS_FOLDERS_SQL OR f.dates != $FTS_DATES_SQL)""",
    )
    suspend fun deleteStaleEntries(): Int

    /** Entrées des médias absents de l'index (nouveaux, ou retirés juste avant comme périmés). */
    @Query(
        """INSERT INTO media_fts (rowid, name, folders, dates)
            SELECT m.id, m.displayName, $FTS_FOLDERS_SQL, $FTS_DATES_SQL FROM media m
            WHERE m.id NOT IN (SELECT rowid FROM media_fts)""",
    )
    suspend fun insertMissingEntries()

    @Query("SELECT COUNT(*) FROM media_fts")
    suspend fun entryCount(): Int

    /**
     * Mise à jour incrémentale, en une transaction : seules les entrées nouvelles, supprimées ou
     * modifiées sont réécrites. Renvoie le nombre d'entrées retirées (supprimées ou périmées).
     */
    @Transaction
    suspend fun refreshIndex(): Int {
        val removed = deleteOrphanEntries() + deleteStaleEntries()
        insertMissingEntries()
        return removed
    }
}
