package fr.eva.cloudgallery.ui.main

import fr.eva.cloudgallery.domain.model.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Test

/** Main screen content: library, permission or "OneDrive only". */
class MainScreenLogicTest {

    @Test
    fun mode_withLocalAccess_isLibrary_whateverTheOneDriveChoice() {
        for (access in listOf(MediaAccess.FULL, MediaAccess.PARTIAL)) {
            for (chosen in listOf(null, false, true)) {
                assertEquals(MainScreenMode.LIBRARY, mainScreenMode(access, cloudAvailable = true, oneDriveOnlyChosen = chosen))
            }
        }
    }

    @Test
    fun mode_withoutAccess_isOneDriveOnly_onlyWhenChosenAndAvailable() {
        assertEquals(MainScreenMode.ONEDRIVE_ONLY, mainScreenMode(MediaAccess.NONE, cloudAvailable = true, oneDriveOnlyChosen = true))
        assertEquals(MainScreenMode.PERMISSION, mainScreenMode(MediaAccess.NONE, cloudAvailable = true, oneDriveOnlyChosen = false))
    }

    @Test
    fun mode_withoutOneDriveInThisBuild_isAlwaysPermission() {
        for (chosen in listOf(null, false, true)) {
            assertEquals(MainScreenMode.PERMISSION, mainScreenMode(MediaAccess.NONE, cloudAvailable = false, oneDriveOnlyChosen = chosen))
        }
    }

    @Test
    fun mode_withoutAccess_waitsForTheStoredChoice() {
        assertEquals(MainScreenMode.LOADING, mainScreenMode(MediaAccess.NONE, cloudAvailable = true, oneDriveOnlyChosen = null))
    }
}
