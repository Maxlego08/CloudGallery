package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync state kept outside memory (a process restart doesn't forget it):
 * - end of the pause requested by Microsoft Graph (Retry-After): no request during the pause;
 * - verified MSAL account ([verifiedAccountId]): the one whose drive matched the index during
 *   the last pass; another signed-in account accesses nothing until it is verified;
 * - other account detected ([accountMismatch]): access blocked from startup, until the purge;
 * - resumption of an interrupted full enumeration ([resumeMeta], journal of seen items in
 *   `noBackupFilesDir`, see EnumerationResumeLogic). The nextLink carries a Graph token: it is
 *   never logged.
 *
 * Dedicated preferences (`cloud_sync.xml`, excluded from backup), read and written only on the
 * I/O dispatcher (then read in memory). Everything is cleared with the OneDrive data (sign-out,
 * purge): these values concern the account whose data is in the index.
 */
@Singleton
class CloudSyncThrottleStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Opened on first use, on the I/O dispatcher (reads the file). */
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    /** Instant (ms) before which no sync may query Graph, 0 otherwise. */
    suspend fun retryNotBefore(): Long = withContext(io) { prefs.getLong(KEY_RETRY_NOT_BEFORE, 0L) }

    suspend fun setRetryNotBefore(until: Long) = withContext(io) {
        prefs.edit { putLong(KEY_RETRY_NOT_BEFORE, until) }
    }

    /** Pause end cleared (successful pass); the verified account is kept. */
    suspend fun clearRetry() = withContext(io) {
        if (prefs.contains(KEY_RETRY_NOT_BEFORE)) prefs.edit { remove(KEY_RETRY_NOT_BEFORE) }
    }

    /** MSAL identifier of the verified account, null if none (empty index, or data from before phase 5). */
    suspend fun verifiedAccountId(): String? = withContext(io) { prefs.getString(KEY_VERIFIED_ACCOUNT, null) }

    suspend fun accountMismatch(): Boolean = withContext(io) { prefs.getBoolean(KEY_ACCOUNT_MISMATCH, false) }

    /** Drive of account [accountId] matches the index: account verified, no more blocking. */
    suspend fun setVerified(accountId: String) = withContext(io) {
        prefs.edit {
            putString(KEY_VERIFIED_ACCOUNT, accountId)
            remove(KEY_ACCOUNT_MISMATCH)
        }
    }

    /** Drive different from the index's: access blocked until the purge. */
    suspend fun setAccountMismatch() = withContext(io) {
        prefs.edit { putBoolean(KEY_ACCOUNT_MISMATCH, true) }
    }

    /** Resume state of the full enumeration in progress or interrupted. */
    internal suspend fun resumeMeta(): EnumerationResumeMeta = withContext(io) {
        EnumerationResumeMeta(
            version = prefs.getString(KEY_RESUME_VERSION, null),
            startedAt = prefs.getLong(KEY_RESUME_STARTED_AT, 0L),
            nextLink = prefs.getString(KEY_RESUME_NEXT_LINK, null),
            pages = prefs.getInt(KEY_RESUME_PAGES, 0),
        )
    }

    /**
     * New full enumeration bound to [version]: the old resume state and its journal are cleared
     * (synchronous write: never an old journal with the new state).
     */
    suspend fun beginEnumeration(version: String, startedAt: Long) = withContext(io) {
        journal.delete()
        prefs.edit(commit = true) {
            removeResume()
            putString(KEY_RESUME_VERSION, version)
            putLong(KEY_RESUME_STARTED_AT, startedAt)
        }
    }

    /** Appends a page's block to the journal, synced to disk before the link is saved. */
    suspend fun appendSeenBlock(block: String) = withContext(io) {
        FileOutputStream(journal, true).use { out ->
            out.write(block.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Link to the next page, after [pages] pages applied and journaled. */
    suspend fun saveNextLink(nextLink: String, pages: Int) = withContext(io) {
        prefs.edit {
            putString(KEY_RESUME_NEXT_LINK, nextLink)
            putInt(KEY_RESUME_PAGES, pages)
        }
    }

    /** Journal content, null if missing, too large or unreadable. */
    suspend fun readSeenJournal(): String? = withContext(io) {
        try {
            if (!journal.isFile || journal.length() > SEEN_JOURNAL_MAX_BYTES) null else journal.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    /** No resume possible any more (enumeration finished, or resume abandoned). */
    suspend fun clearResume() = withContext(io) {
        prefs.edit(commit = true) { removeResume() }
        journal.delete()
    }

    /** Everything is cleared (OneDrive data purged), resume journal included. */
    suspend fun clear() = withContext(io) {
        prefs.edit(commit = true) { clear() }
        journal.delete()
    }

    private val journal: File get() = File(context.noBackupFilesDir, JOURNAL_FILE)

    private fun SharedPreferences.Editor.removeResume() {
        remove(KEY_RESUME_VERSION)
        remove(KEY_RESUME_STARTED_AT)
        remove(KEY_RESUME_NEXT_LINK)
        remove(KEY_RESUME_PAGES)
    }

    private companion object {
        const val FILE_NAME = "cloud_sync"
        const val KEY_RETRY_NOT_BEFORE = "retry_not_before"
        const val KEY_VERIFIED_ACCOUNT = "verified_account_id"
        const val KEY_ACCOUNT_MISMATCH = "account_mismatch"
        const val KEY_RESUME_VERSION = "resume_version"
        const val KEY_RESUME_STARTED_AT = "resume_started_at"
        const val KEY_RESUME_NEXT_LINK = "resume_next_link"
        const val KEY_RESUME_PAGES = "resume_pages"

        /** Journal of items seen by the current full enumeration (excluded from backup). */
        const val JOURNAL_FILE = "onedrive_enumeration_seen.log"
    }
}
