package com.pwde.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pwde.app.data.games.CustomGamesRepository
import com.pwde.app.data.local.CalibrationProfile
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.ProfileRepository.Companion.CALIBRATION_COLLECTION
import com.pwde.app.data.local.ProfileRepository.Companion.GAME_COLLECTION
import com.pwde.app.data.local.PwdeDatabase
import com.pwde.app.data.remote.CloudDoc
import com.pwde.app.data.remote.CloudStore
import com.pwde.app.data.remote.CloudSyncEngine
import com.pwde.app.data.remote.MergeStep
import com.pwde.app.data.remote.SyncLedger
import com.pwde.app.data.remote.planMerge
import com.pwde.app.ui.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class CloudMergeTest {
    private data class Rec(val remoteId: String?, val updatedAt: Long)

    private fun plan(local: List<Rec>, remote: List<CloudDoc>) = planMerge(local, remote, { it.remoteId }, { it.updatedAt })

    private fun doc(id: String, at: Long, deleted: Boolean = false) =
        CloudDoc(id, mapOf(CloudDoc.FIELD_UPDATED_AT to at, CloudDoc.FIELD_DELETED to deleted))

    @Test
    fun newLocalRecords_arePushed_andNewRemoteOnes_pulled() {
        val local = Rec(null, 5)
        val remote = doc("r1", 5)
        assertEquals(listOf(MergeStep.Push(local), MergeStep.Pull<Rec>(remote, null)), plan(listOf(local), listOf(remote)))
    }

    @Test
    fun newerSideWins_andEqualIsLeftAlone() {
        val newerHere = Rec("a", 10)
        val newerThere = Rec("b", 10)
        val same = Rec("c", 10)
        val steps = plan(listOf(newerHere, newerThere, same), listOf(doc("a", 5), doc("b", 20), doc("c", 10)))
        assertEquals(listOf(MergeStep.Push(newerHere), MergeStep.Pull(doc("b", 20), newerThere)), steps)
    }

    @Test
    fun tombstones_deleteUnlessEditedAfterwards() {
        val stale = Rec("a", 5)
        val editedLater = Rec("b", 30)
        val steps = plan(listOf(stale, editedLater), listOf(doc("a", 10, deleted = true), doc("b", 10, deleted = true), doc("c", 1, deleted = true)))
        assertEquals(listOf(MergeStep.DeleteLocal(stale), MergeStep.Push(editedLater)), steps)
    }

    @Test
    fun syncedRecordMissingFromTheCloud_isPushedAgain() {
        val local = Rec("gone", 5)
        assertEquals(listOf(MergeStep.Push(local)), plan(listOf(local), emptyList()))
    }
}

/** Two phones sharing one fake cloud. */
@RunWith(RobolectricTestRunner::class)
class CloudSyncEngineTest {
    private class FakeCloud : CloudStore {
        val docs = LinkedHashMap<String, MutableMap<String, Map<String, Any?>>>()
        private var nextId = 0

        override suspend fun list(uid: String, collection: String) =
            docs["$uid/$collection"].orEmpty().map { (id, data) -> CloudDoc(id, data) }
        override suspend fun get(uid: String, collection: String, id: String) =
            docs["$uid/$collection"]?.get(id)?.let { CloudDoc(id, it) }
        override suspend fun put(uid: String, collection: String, id: String, data: Map<String, Any?>) {
            docs.getOrPut("$uid/$collection") { LinkedHashMap() }[id] = data
        }
        override fun newId(uid: String, collection: String) = "doc${nextId++}"
        override suspend fun putUser(uid: String, data: Map<String, Any?>) = Unit
    }

    private inner class Phone {
        val db: PwdeDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PwdeDatabase::class.java,
        ).allowMainThreadQueries().build()
        val ledger = SyncLedger.InMemory()
        val profiles = ProfileRepository(db.calibrationProfileDao(), db.gameProfileDao(), { c, id -> ledger.recordDelete(c, id) }) { now }
        val controls = ControlsRepository(db.controlSettingsDao(), profiles) { now }
        val settings = FakeSettingsRepository()
        val games = CustomGamesRepository.InMemory()
        val engine = CloudSyncEngine(cloud, profiles, controls, settings, games, ledger) { now }
    }

    private val cloud = FakeCloud()
    private var now = 1_000L
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        a = Phone()
        b = Phone()
    }

    @After
    fun tearDown() {
        a.db.close()
        b.db.close()
    }

    private fun calibration(name: String) =
        CalibrationProfile(name = name, inputMode = "HEAD_FACE", voiceMatchMode = "WORD_ANYWHERE", voiceActivationMode = "IMMEDIATE", createdAt = 0, updatedAt = 0)

    @Test
    fun profilesAndTheirLinks_reachTheOtherPhone() = runTest {
        val calId = a.profiles.saveCalibrationProfile(calibration("Mine"))
        a.profiles.saveGameProfile(GameProfile(gameId = "mlbb", gameName = "MLBB", profileName = "Main", calibrationProfileId = calId, createdAt = 0, updatedAt = 0))
        a.games.add("Chess")
        a.engine.sync("u1")

        b.engine.sync("u1")

        val cal = b.profiles.allCalibrationProfiles().single()
        val game = b.profiles.allGameProfiles().single()
        assertEquals("Mine", cal.name)
        assertEquals("Main", game.profileName)
        assertEquals(cal.id, game.calibrationProfileId)
        assertEquals(listOf("Chess"), b.games.names.first())
    }

    @Test
    fun laterEditWins_andDeletesPropagate() = runTest {
        a.profiles.saveCalibrationProfile(calibration("Old"))
        a.engine.sync("u1")
        b.engine.sync("u1")

        now = 2_000L
        val onB = b.profiles.allCalibrationProfiles().single()
        b.profiles.saveCalibrationProfile(onB.copy(name = "Renamed"))
        b.engine.sync("u1")
        a.engine.sync("u1")
        val onA = a.profiles.allCalibrationProfiles().single()
        assertEquals("Renamed", onA.name)

        now = 3_000L
        a.profiles.deleteCalibrationProfile(onA)
        a.engine.sync("u1")
        b.engine.sync("u1")
        assertTrue(b.profiles.allCalibrationProfiles().isEmpty())
        assertEquals(true, cloud.docs["u1/$CALIBRATION_COLLECTION"]!![onA.remoteId]!![CloudDoc.FIELD_DELETED])
    }

    @Test
    fun workingControls_syncWithTheirActiveCalibration() = runTest {
        val calId = a.profiles.saveCalibrationProfile(calibration("Active"))
        a.controls.applyCalibration(a.profiles.getCalibrationProfile(calId)!!)
        a.engine.sync("u1")

        b.engine.sync("u1")

        val controls = b.controls.syncedEntity()
        assertNotNull(controls)
        assertEquals(b.profiles.allCalibrationProfiles().single().id, controls!!.activeCalibrationProfileId)
    }

    @Test
    fun switchingAccounts_uploadsLocalProfilesAsNew() = runTest {
        a.profiles.saveGameProfile(GameProfile(gameId = "mlbb", gameName = "MLBB", profileName = "Main", calibrationProfileId = null, createdAt = 0, updatedAt = 0))
        a.engine.sync("u1")
        a.engine.sync("u2")

        assertEquals(1, cloud.docs["u1/$GAME_COLLECTION"]!!.size)
        assertEquals(1, cloud.docs["u2/$GAME_COLLECTION"]!!.size)
        assertNull(cloud.docs["u2/$GAME_COLLECTION"]!!.keys.firstOrNull { it in cloud.docs["u1/$GAME_COLLECTION"]!!.keys })
    }
}
