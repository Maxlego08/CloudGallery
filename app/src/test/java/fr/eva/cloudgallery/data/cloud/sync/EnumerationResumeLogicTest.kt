package fr.eva.cloudgallery.data.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resuming a full OneDrive enumeration: journal of seen items and resume conditions. */
class EnumerationResumeLogicTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_000 * hour
    private val version = "drive1|s1"
    private val link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=abc"

    private fun m(id: String) = SeenEntry(SeenOp.MEDIA, id)
    private fun f(id: String) = SeenEntry(SeenOp.FOLDER, id)
    private fun r(id: String) = SeenEntry(SeenOp.REMOVE, id)

    @Test
    fun journal_replaysPagesInOrder_likeTheLiveRun() {
        val text = encodeSeenBlock(listOf(f("D1"), m("A!1"), m("B!2"))) +
            encodeSeenBlock(listOf(r("A!1"), f("D2"))) +
            encodeSeenBlock(listOf(m("A!1"), r("D1"))) +
            encodeSeenBlock(emptyList())
        val replay = replaySeenJournal(text)
        assertNotNull(replay)
        assertEquals(setOf("A!1", "B!2"), replay!!.seen)
        assertEquals(setOf("D2"), replay.seenFolders)
        assertEquals(4, replay.completeBlocks)
    }

    @Test
    fun journal_ignoresAnInterruptedLastBlock() {
        val complete = encodeSeenBlock(listOf(m("A")))
        // Stopped while writing: block without an end line, then a truncated line.
        val replay = replaySeenJournal(complete + "M\tB\nM\tC")
        assertEquals(setOf("A"), replay!!.seen)
        assertEquals(1, replay.completeBlocks)
        // Whole block without an end line: ignored too.
        assertEquals(setOf("A"), replaySeenJournal(complete + "M\tB\n")!!.seen)
    }

    @Test
    fun journal_replayedPageTwice_givesTheSameSets() {
        val page = encodeSeenBlock(listOf(m("A"), r("B")))
        val once = replaySeenJournal(encodeSeenBlock(listOf(m("B"))) + page)!!
        val twice = replaySeenJournal(encodeSeenBlock(listOf(m("B"))) + page + page)!!
        assertEquals(once.seen, twice.seen)
        assertEquals(setOf("A"), twice.seen)
    }

    @Test
    fun journal_withAMalformedLine_isUnreadable() {
        assertNull(replaySeenJournal("X\tA\nE\n"))
        assertNull(replaySeenJournal("MA\nE\n"))
        assertNull(replaySeenJournal("M\t\nE\n"))
        assertEquals(0, replaySeenJournal("")!!.completeBlocks)
    }

    @Test
    fun journal_refusesIdsThatWouldBreakTheFormat() {
        assertTrue(isJournalSafeId("ABC123!456"))
        assertFalse(isJournalSafeId("a\nb"))
        assertFalse(isJournalSafeId("a\tb"))
        assertFalse(isJournalSafeId(""))
    }

    private fun meta(
        version: String? = this.version,
        startedAt: Long = now - hour,
        nextLink: String? = link,
        pages: Int = 3,
    ) = EnumerationResumeMeta(version, startedAt, nextLink, pages)

    private fun journal(blocks: Int) = SeenReplay(emptySet(), emptySet(), blocks)

    @Test
    fun resume_whenRecentSameDriveAndJournalComplete() {
        assertTrue(canResumeEnumeration(meta(), version, now, journal(3)))
        // One more applied page than the saved link: replayed harmlessly.
        assertTrue(canResumeEnumeration(meta(), version, now, journal(4)))
    }

    @Test
    fun noResume_whenStateIsStaleOrInconsistent() {
        // Different drive or different field list.
        assertFalse(canResumeEnumeration(meta(version = "drive2|s1"), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(version = null), version, now, journal(3)))
        // Too old, clock moved back, never started.
        assertFalse(canResumeEnumeration(meta(startedAt = now - 25 * hour), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(startedAt = now + hour), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(startedAt = 0L), version, now, journal(3)))
        // No link, no page, link outside Microsoft Graph.
        assertFalse(canResumeEnumeration(meta(nextLink = null), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(pages = 0), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(nextLink = "https://example.com/delta"), version, now, journal(3)))
        assertFalse(canResumeEnumeration(meta(nextLink = "http://graph.microsoft.com/v1.0/x"), version, now, journal(3)))
        // Journal unreadable or behind the saved link.
        assertFalse(canResumeEnumeration(meta(), version, now, null))
        assertFalse(canResumeEnumeration(meta(), version, now, journal(2)))
    }

    @Test
    fun rejectedResumeLink_onlyForClientErrorsOtherThanAuthAndThrottling() {
        assertTrue(isRejectedResumeLink(400))
        assertTrue(isRejectedResumeLink(404))
        assertFalse(isRejectedResumeLink(401))
        assertFalse(isRejectedResumeLink(429))
        assertFalse(isRejectedResumeLink(500))
        assertFalse(isRejectedResumeLink(503))
    }

    @Test
    fun syncRun_recordsPageOpsOnlyInFullEnumeration_andResumesWithReplayedSets() {
        val incremental = CloudSyncRun(full = false)
        incremental.markSeen("A")
        incremental.markRemoved("B")
        assertTrue(incremental.drainPageOps().isEmpty())

        val full = CloudSyncRun(full = true)
        full.markSeenFolder("D")
        full.markSeen("A")
        full.markRemoved("D")
        assertEquals(listOf(f("D"), m("A"), r("D")), full.drainPageOps())
        assertTrue(full.drainPageOps().isEmpty())
        assertEquals(setOf("A"), full.seen)
        assertEquals(emptySet<String>(), full.seenFolders)

        val resumed = CloudSyncRun.resumed(setOf("A"), setOf("D"))
        assertTrue(resumed.full)
        resumed.markSeen("B")
        assertEquals(setOf("A", "B"), resumed.seen)
        assertEquals(setOf("D"), resumed.seenFolders)
    }
}
