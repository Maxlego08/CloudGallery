package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.data.cloud.graph.isTrustedGraphUrl

/*
 * Resumption of an interrupted full OneDrive enumeration (pure logic, tested on the JVM).
 *
 * At the end of its pass, a full enumeration hides the items and folders that were not seen.
 * To resume it from the last applied page without wrongly hiding those of the previous pages,
 * the operations on the "seen" sets are journaled page by page (one block per page, terminated
 * by an end line), then replayed on resume. Write order for each page: the page's Room
 * transaction, then the journal block (synced to disk), then nextLink and page count. A page
 * replayed after a stop is idempotent (index and sets).
 */

/**
 * Maximum age of a resumed enumeration: beyond it, its continuation links (nextLink, which carry
 * a Graph token) may have expired and the index may have changed too much; it restarts from
 * the beginning.
 */
internal const val ENUMERATION_RESUME_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Journal larger than this (≈ several million items): unreadable, resume abandoned. */
internal const val SEEN_JOURNAL_MAX_BYTES = 64L * 1024 * 1024

/** Operation on the "seen" sets of a full enumeration. */
internal enum class SeenOp(val code: Char) {
    /** Media received. */
    MEDIA('M'),

    /** Folder received. */
    FOLDER('F'),

    /** Item reported deleted: removed from both sets. */
    REMOVE('R'),
}

internal data class SeenEntry(val op: SeenOp, val id: String)

private const val BLOCK_END = 'E'
private const val SEPARATOR = '\t'

/** OneDrive identifier that can be journaled as is (one line per operation). */
internal fun isJournalSafeId(id: String): Boolean = id.isNotEmpty() && id.none { it == '\n' || it == '\r' || it == SEPARATOR }

/** Journal block for a page: one line per operation, then the end line. */
internal fun encodeSeenBlock(entries: List<SeenEntry>): String = buildString {
    for (entry in entries) {
        require(isJournalSafeId(entry.id)) { "Identifier cannot be journaled" }
        append(entry.op.code).append(SEPARATOR).append(entry.id).append('\n')
    }
    append(BLOCK_END).append('\n')
}

/** Sets rebuilt from the journal's complete blocks. */
internal class SeenReplay(val seen: Set<String>, val seenFolders: Set<String>, val completeBlocks: Int)

/**
 * Replays the journal: only complete blocks count (a block interrupted by a stop is ignored:
 * its page will be replayed). Null if a line of a complete block is unreadable.
 */
internal fun replaySeenJournal(text: String): SeenReplay? {
    val seen = HashSet<String>()
    val seenFolders = HashSet<String>()
    var blocks = 0
    val pending = ArrayList<SeenEntry>()
    // Last line without a line break: interrupted write, ignored.
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
 * Saved resume state: enumeration bound to [version] (`drive|select`), started at [startedAt],
 * with [pages] pages applied and journaled, to be continued from [nextLink].
 */
internal data class EnumerationResumeMeta(
    val version: String?,
    val startedAt: Long,
    val nextLink: String?,
    val pages: Int,
)

/**
 * Resume is possible: same drive and same field list, recent enumeration (clock not moved
 * back), Microsoft Graph continuation link, at least one page applied and all its pages present
 * in the journal (one extra block is allowed: page applied but link not yet saved, replayed
 * harmlessly).
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
 * Resumed continuation link rejected by Graph (invalid request, expired or unknown link): the
 * resume is abandoned and the enumeration restarts from the beginning, only once.
 */
internal fun isRejectedResumeLink(status: Int): Boolean = status in 400..499 && status != 401 && status != 429
