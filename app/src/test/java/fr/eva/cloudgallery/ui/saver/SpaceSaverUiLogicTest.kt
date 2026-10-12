package fr.eva.cloudgallery.ui.saver

import fr.eva.cloudgallery.domain.model.SaverAge
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ecran Economiseur d'espace : filtre d'ancienneté, sélection, résumé, lots. */
class SpaceSaverUiLogicTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun candidate(id: Long, daysAgo: Long, size: Long = 100) =
        SpaceSaverCandidate(id, id, "u$id", size, 0, false, "f$id", now - daysAgo * day)

    private val all = listOf(candidate(1, 0), candidate(2, 30), candidate(3, 31), candidate(4, 100), candidate(5, 400))

    @Test
    fun ageFilter_keepsCopiesTakenBeforeTheThreshold() {
        assertEquals(5, filterByAge(all, SaverAge.ALL, now).size)
        // Exactement 30 jours : comprise.
        assertEquals(listOf(2L, 3L, 4L, 5L), filterByAge(all, SaverAge.DAYS_30, now).map { it.sourceId })
        assertEquals(listOf(4L, 5L), filterByAge(all, SaverAge.DAYS_90, now).map { it.sourceId })
        assertEquals(listOf(5L), filterByAge(all, SaverAge.YEAR, now).map { it.sourceId })
    }

    @Test
    fun defaultAge_is30Days() {
        assertEquals(SaverAge.DAYS_30, SpaceSaverUiState().age)
    }

    @Test
    fun selection_allCheckedExceptDeselected_andSummarized() {
        val filtered = filterByAge(all, SaverAge.DAYS_30, now)
        val selected = selectedIn(filtered, deselected = setOf(3L, 1L))
        assertEquals(listOf(2L, 4L, 5L), selected.map { it.sourceId })
        assertEquals(SaverSummary(3, 300), summaryOf(selected))
        assertEquals(SaverSummary(0, 0), summaryOf(emptyList()))
    }

    @Test
    fun stateFiltered_usesTheCurrentAge() {
        val state = SpaceSaverUiState(
            list = fr.eva.cloudgallery.domain.model.SpaceSaverList(all, 0),
            age = SaverAge.DAYS_90,
        )
        assertEquals(listOf(4L, 5L), state.filtered(now).map { it.sourceId })
        assertEquals(emptyList<SpaceSaverCandidate>(), SpaceSaverUiState().filtered(now))
    }

    @Test
    fun trashBatches_splitLargeSelections() {
        val many = (1L..2_500L).map { candidate(it, 40) }
        val batches = trashBatches(many)
        assertEquals(listOf(1_000, 1_000, 500), batches.map { it.size })
        assertEquals(many, batches.flatten())
        assertEquals(emptyList<List<SpaceSaverCandidate>>(), trashBatches(emptyList()))
    }
}
