package com.pwde.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pwde.app.data.local.CalibrationProfile
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.PwdeDatabase
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.GestureAction
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.data.model.VoiceShortcut
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private lateinit var db: PwdeDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var controls: ControlsRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PwdeDatabase::class.java).allowMainThreadQueries().build()
        profiles = ProfileRepository(db.calibrationProfileDao(), db.gameProfileDao()) { now }
        controls = ControlsRepository(db.controlSettingsDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun startsEmpty() = runTest {
        assertTrue(profiles.calibrationProfiles.first().isEmpty())
        assertTrue(profiles.gameProfiles.first().isEmpty())
    }

    @Test
    fun saveCalibration_setsTimestampsAndUpdates() = runTest {
        val id = profiles.saveCalibrationProfile(calibration("Morning"))
        val saved = profiles.getCalibrationProfile(id)!!
        assertEquals(1_000L, saved.createdAt)

        now = 2_000L
        profiles.saveCalibrationProfile(saved.copy(name = "Evening"))
        val updated = profiles.calibrationProfiles.first().single()
        assertEquals("Evening", updated.name)
        assertEquals(1_000L, updated.createdAt)
        assertEquals(2_000L, updated.updatedAt)
    }

    @Test
    fun deletingCalibration_keepsGameProfileButClearsLink() = runTest {
        val calibrationId = profiles.saveCalibrationProfile(calibration("Default"))
        val gameId = profiles.saveGameProfile(
            GameProfile(
                gameId = "mobile_legends",
                gameName = "Mobile Legends",
                profileName = "ML – Head + Voice",
                calibrationProfileId = calibrationId,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        assertEquals(1, profiles.gameProfilesFor("mobile_legends").first().size)

        profiles.deleteCalibrationProfile(profiles.getCalibrationProfile(calibrationId)!!)

        assertNull(profiles.getGameProfile(gameId)!!.calibrationProfileId)
    }

    @Test
    fun controls_persistGesturesAndVoiceSettings() = runTest {
        controls.setGesture(GestureAction.SELECT, FacialGesture.OPEN_MOUTH)
        controls.setGesture(GestureAction.BACK, FacialGesture.TILT_LEFT)
        controls.setGesture(GestureAction.BACK, null)
        controls.setVoiceMatchMode(VoiceMatchMode.EXACT)
        controls.setVoiceShortcut(VoiceShortcut.CURSOR_MODE, "pointer")

        val config = controls.config.first()
        // SELECT was set explicitly; Recenter keeps its default; BACK was set and then cleared again.
        assertEquals(
            mapOf(
                GestureAction.SELECT to FacialGesture.OPEN_MOUTH,
                GestureAction.RECENTER to FacialGesture.OPEN_MOUTH,
            ),
            config.gestureAssignments,
        )
        assertEquals(VoiceMatchMode.EXACT, config.voiceMatchMode)
        assertEquals("pointer", config.voiceShortcuts[VoiceShortcut.CURSOR_MODE])
    }

    private fun calibration(name: String) = CalibrationProfile(
        name = name,
        inputMode = "HEAD_FACE",
        voiceMatchMode = "WORD_ANYWHERE",
        voiceActivationMode = "IMMEDIATE",
        createdAt = 0,
        updatedAt = 0,
    )
}
