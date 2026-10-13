package fr.eva.cloudgallery.ui.securefolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFolderLogicTest {

    private fun local(id: Long, cloud: Boolean = false) = SecureFolderCandidate(id, hasLocalFile = true, hasCloud = cloud)
    private fun cloudOnly(id: Long) = SecureFolderCandidate(id, hasLocalFile = false, hasCloud = true)

    @Test
    fun plan_onlyCloudItems_nothingLocal() {
        assertEquals(SecureFolderPlan.NothingLocal, planSecureFolderMove(listOf(cloudOnly(1), cloudOnly(2))))
    }

    @Test
    fun plan_emptySelection_nothingLocal() {
        assertEquals(SecureFolderPlan.NothingLocal, planSecureFolderMove(emptyList()))
    }

    @Test
    fun plan_mixedSelection_keepsLocalInOrderAndCountsOthers() {
        val plan = planSecureFolderMove(listOf(local(3), cloudOnly(1), local(2, cloud = true), local(5)))
        assertEquals(SecureFolderPlan.Ready(ids = listOf(3L, 2L, 5L), alsoOnOneDrive = 1, skippedCloudOnly = 1), plan)
    }

    @Test
    fun plan_aboveMax_tooMany() {
        val plan = planSecureFolderMove((1L..4L).map { local(it) }, max = 3)
        assertEquals(SecureFolderPlan.TooMany(3), plan)
    }

    @Test
    fun plan_cloudItemsDoNotCountTowardMax_ready() {
        val plan = planSecureFolderMove((1L..3L).map { local(it) } + cloudOnly(9), max = 3)
        assertTrue(plan is SecureFolderPlan.Ready)
        assertEquals(1, (plan as SecureFolderPlan.Ready).skippedCloudOnly)
    }

    @Test
    fun actionVisible_requiresAppAndLocalFile() {
        assertTrue(secureFolderActionVisible(secureFolderInstalled = true, localCount = 1))
        assertFalse(secureFolderActionVisible(secureFolderInstalled = false, localCount = 5))
        assertFalse(secureFolderActionVisible(secureFolderInstalled = true, localCount = 0))
    }

    @Test
    fun shareReturn_whileSharing_asksConfirmation() {
        assertEquals(SecureFolderStep.CONFIRM_TRASH, stepAfterShareReturn(SecureFolderStep.SHARING))
    }

    @Test
    fun shareReturn_otherSteps_unchanged() {
        assertEquals(SecureFolderStep.INTRO, stepAfterShareReturn(SecureFolderStep.INTRO))
        assertEquals(SecureFolderStep.CONFIRM_TRASH, stepAfterShareReturn(SecureFolderStep.CONFIRM_TRASH))
        assertNull(stepAfterShareReturn(null))
    }
}
