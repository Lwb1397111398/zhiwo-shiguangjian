package com.zhiwo.shiguangjian.data.profile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileEditorSessionBlocklistTest {

    private class FakeStore(
        var profile: UserProfile = UserProfile(),
        var blocklist: ProfileBlocklist = ProfileBlocklist(),
        var autoUpdate: Boolean = true
    ) {
        var failBlocklist = false
        var failProfile = false
        val loadProfile: suspend () -> UserProfile = { profile }
        val saveProfile: suspend (UserProfile) -> Unit = {
            if (failProfile) throw IllegalStateException("profile save fail")
            profile = it
        }
        val loadBlocklist: suspend () -> ProfileBlocklist = { blocklist }
        val saveBlocklist: suspend (ProfileBlocklist) -> Unit = {
            if (failBlocklist) throw IllegalStateException("blocklist save fail")
            blocklist = it
        }
        val loadAuto: suspend () -> Boolean = { autoUpdate }
        val saveAuto: suspend (Boolean) -> Unit = { autoUpdate = it }
    }

    private fun sessionOf(store: FakeStore) = UserProfileEditorSession(
        loadProfile = store.loadProfile,
        saveProfile = store.saveProfile,
        loadBlocklist = store.loadBlocklist,
        saveBlocklist = store.saveBlocklist,
        loadAutoUpdate = store.loadAuto,
        saveAutoUpdate = store.saveAuto
    )

    @Test
    fun `delete then save puts item into blocklist`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("A", "B")))
        val session = sessionOf(store)
        session.reload()
        session.removeText(UserProfileEditorSession.Field.PREFERENCES, 1)
        assertTrue(session.save(false))
        assertEquals(listOf("A"), store.profile.preferences)
        assertTrue(ProfileBlocklistCodec.isBlocked(store.blocklist.preferences, "B"))
    }

    @Test
    fun `discard after delete does not persist blocklist`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("A", "B")))
        val session = sessionOf(store)
        session.reload()
        session.removeText(UserProfileEditorSession.Field.PREFERENCES, 1)
        session.markCleanForDiscard()
        session.reload()
        assertEquals(listOf("A", "B"), session.draft.value.preferences)
        assertTrue(session.blocklist.value.isEmpty())
        assertTrue(store.blocklist.isEmpty())
    }

    @Test
    fun `edit blocks old value and unblocks new if needed`() = runBlocking {
        val store = FakeStore(
            profile = UserProfile(preferences = listOf("旧值")),
            blocklist = ProfileBlocklist(preferences = listOf("新值"))
        )
        val session = sessionOf(store)
        session.reload()
        session.updateText(UserProfileEditorSession.Field.PREFERENCES, 0, "新值")
        assertTrue(session.save(false))
        assertTrue(ProfileBlocklistCodec.isBlocked(store.blocklist.preferences, "旧值"))
        assertFalse(ProfileBlocklistCodec.isBlocked(store.blocklist.preferences, "新值"))
        assertEquals(listOf("新值"), store.profile.preferences)
    }

    @Test
    fun `manual add unblocks silently`() = runBlocking {
        val store = FakeStore(blocklist = ProfileBlocklist(preferences = listOf("直接建议")))
        val session = sessionOf(store)
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "直接建议")
        assertTrue(session.save(false))
        assertFalse(ProfileBlocklistCodec.isBlocked(store.blocklist.preferences, "直接建议"))
        assertEquals(listOf("直接建议"), store.profile.preferences)
    }

    @Test
    fun `clear blocks all current items`() = runBlocking {
        val store = FakeStore(
            UserProfile(
                preferences = listOf("A"),
                personality = listOf(PersonalityTrait("冷静", 0.5)),
                recentStates = listOf(RecentState("备考", "2099-01-01"))
            )
        )
        val session = sessionOf(store)
        session.reload()
        assertTrue(session.clearProfile())
        assertTrue(store.profile.isEmpty())
        assertTrue(ProfileBlocklistCodec.isBlocked(store.blocklist.preferences, "A"))
        assertTrue(ProfileBlocklistCodec.isBlocked(store.blocklist.personalityTraits, "冷静"))
        assertTrue(ProfileBlocklistCodec.isBlocked(store.blocklist.recentStates, "备考"))
    }

    @Test
    fun `unblock then save removes from blocklist`() = runBlocking {
        val store = FakeStore(blocklist = ProfileBlocklist(preferences = listOf("A", "B")))
        val session = sessionOf(store)
        session.reload()
        session.unblock(BlocklistCategory.PREFERENCES, "A")
        assertTrue(session.save(false))
        assertEquals(listOf("B"), store.blocklist.preferences)
    }

    @Test
    fun `blocklist save failure keeps draft dirty and error`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("A")))
        val session = sessionOf(store)
        session.reload()
        session.removeText(UserProfileEditorSession.Field.PREFERENCES, 0)
        store.failBlocklist = true
        assertFalse(session.save(false))
        assertTrue(session.dirty.value)
        assertEquals("blocklist save fail", session.error.value)
        // profile 未写空（因为 blocklist 先失败）
        assertEquals(listOf("A"), store.profile.preferences)
    }

    @Test
    fun `autoUpdate switch is dirty and saved`() = runBlocking {
        val store = FakeStore(autoUpdate = true)
        val session = sessionOf(store)
        session.reload()
        session.setAutoUpdateEnabled(false)
        assertTrue(session.dirty.value)
        assertTrue(session.save(false))
        assertFalse(store.autoUpdate)
        assertFalse(session.dirty.value)
    }
}
