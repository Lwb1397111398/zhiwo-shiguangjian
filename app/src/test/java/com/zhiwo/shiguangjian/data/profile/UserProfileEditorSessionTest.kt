package com.zhiwo.shiguangjian.data.profile

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileEditorSessionTest {

    private class FakeStore(var profile: UserProfile = UserProfile()) {
        var failSave: Boolean = false
        var saveCount: Int = 0
        val load: suspend () -> UserProfile = { profile }
        val save: suspend (UserProfile) -> Unit = { p ->
            saveCount++
            if (failSave) throw IllegalStateException("模拟保存失败")
            profile = p
        }
    }

    @Test
    fun `load existing profile`() = runBlocking {
        val store = FakeStore(
            UserProfile(preferences = listOf("喜欢直接建议"), supportStyle = listOf("先共情"))
        )
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        assertEquals(listOf("喜欢直接建议"), session.draft.value.preferences)
        assertFalse(session.dirty.value)
        assertFalse(session.loading.value)
    }

    @Test
    fun `add edit delete text items`() = runBlocking {
        val store = FakeStore()
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "  A  ")
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "B")
        assertEquals(listOf("A", "B"), session.draft.value.preferences)
        assertTrue(session.dirty.value)
        session.updateText(UserProfileEditorSession.Field.PREFERENCES, 1, "B2")
        assertEquals(listOf("A", "B2"), session.draft.value.preferences)
        session.removeText(UserProfileEditorSession.Field.PREFERENCES, 0)
        assertEquals(listOf("B2"), session.draft.value.preferences)
    }

    @Test
    fun `duplicate and max limit rejected`() = runBlocking {
        val store = FakeStore()
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.addText(UserProfileEditorSession.Field.STABLE_FACTS, "事实")
        session.addText(UserProfileEditorSession.Field.STABLE_FACTS, "事实")
        assertEquals(1, session.draft.value.stableFacts.size)
        assertEquals("已存在相同内容", session.error.value)
        repeat(7) { session.addText(UserProfileEditorSession.Field.STABLE_FACTS, "x$it") }
        assertEquals(8, session.draft.value.stableFacts.size)
        session.addText(UserProfileEditorSession.Field.STABLE_FACTS, "overflow")
        assertTrue(session.error.value!!.contains("最多"))
    }

    @Test
    fun `user delete then direct save is not restored by merge`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("A", "B")))
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.removeText(UserProfileEditorSession.Field.PREFERENCES, 1) // 删 B
        assertTrue(session.save(exitAfter = false))
        assertEquals(listOf("A"), store.profile.preferences)
        // 再次加载不应恢复 B
        session.reload()
        assertEquals(listOf("A"), session.draft.value.preferences)
    }

    @Test
    fun `save failure keeps draft and dirty`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("旧")))
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "新")
        assertTrue(session.dirty.value)
        store.failSave = true
        assertFalse(session.save(exitAfter = false))
        assertTrue(session.dirty.value)
        assertEquals(listOf("旧", "新"), session.draft.value.preferences)
        assertEquals("模拟保存失败", session.error.value)
        // store 未被写入
        assertEquals(listOf("旧"), store.profile.preferences)
    }

    @Test
    fun `save success clears dirty`() = runBlocking {
        val store = FakeStore()
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "X")
        assertTrue(session.save(exitAfter = false))
        assertFalse(session.dirty.value)
        assertEquals(listOf("X"), store.profile.preferences)
    }

    @Test
    fun `clear success writes empty profile only`() = runBlocking {
        val store = FakeStore(
            UserProfile(
                preferences = listOf("A"),
                personality = listOf(PersonalityTrait("冷静", 0.6))
            )
        )
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        assertTrue(session.clearProfile())
        assertTrue(store.profile.isEmpty())
        assertTrue(session.draft.value.isEmpty())
        assertFalse(session.dirty.value)
    }

    @Test
    fun `clear failure keeps data`() = runBlocking {
        val store = FakeStore(UserProfile(preferences = listOf("A")))
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        store.failSave = true
        assertFalse(session.clearProfile())
        assertEquals(listOf("A"), session.draft.value.preferences)
        assertEquals(listOf("A"), store.profile.preferences)
        assertEquals("模拟保存失败", session.error.value)
    }

    @Test
    fun `damaged load becomes empty editable profile`() = runBlocking {
        val session = UserProfileEditorSession(
            loadProfile = { throw IllegalStateException("损坏") },
            saveProfile = {}
        )
        session.reload()
        assertTrue(session.draft.value.isEmpty())
        assertEquals("加载失败，已使用空画像", session.error.value)
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "可继续编辑")
        assertEquals(listOf("可继续编辑"), session.draft.value.preferences)
        assertTrue(session.dirty.value)
    }

    @Test
    fun `second save while already saving returns false`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = UserProfileEditorSession(
            loadProfile = { UserProfile() },
            saveProfile = { gate.await() }
        )
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "A")
        val first = async { session.save(false) }
        while (!session.saving.value) {
            kotlinx.coroutines.delay(1)
        }
        val second = session.save(false)
        assertFalse(second)
        gate.complete(Unit)
        assertTrue(first.await())
    }

    @Test
    fun `markCleanForDiscard clears dirty immediately`() = runBlocking {
        val store = FakeStore()
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        session.addText(UserProfileEditorSession.Field.PREFERENCES, "A")
        assertTrue(session.dirty.value)
        session.markCleanForDiscard()
        assertFalse(session.dirty.value)
    }

    @Test
    fun `expired recent states dropped on save`() = runBlocking {
        val store = FakeStore(
            UserProfile(
                recentStates = listOf(
                    RecentState("过期", "2020-01-01"),
                    RecentState("有效", "2099-12-31")
                )
            )
        )
        val session = UserProfileEditorSession(loadProfile = store.load, saveProfile = store.save)
        session.reload()
        // 加载时已过滤展示
        assertEquals(1, session.draft.value.recentStates.size)
        session.save(false)
        assertEquals(1, store.profile.recentStates.size)
        assertEquals("有效", store.profile.recentStates[0].content)
    }
}
