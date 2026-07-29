package com.zhiwo.shiguangjian.data.profile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 画像编辑会话：纯 Kotlin，可单测。
 * 草稿同时包含 profile / blocklist / autoUpdateEnabled。
 */
class UserProfileEditorSession(
    private val loadProfile: suspend () -> UserProfile,
    private val saveProfile: suspend (UserProfile) -> Unit,
    private val loadBlocklist: suspend () -> ProfileBlocklist = { ProfileBlocklist() },
    private val saveBlocklist: suspend (ProfileBlocklist) -> Unit = {},
    private val loadAutoUpdate: suspend () -> Boolean = { true },
    private val saveAutoUpdate: suspend (Boolean) -> Unit = {}
) {
    private val _draft = MutableStateFlow(UserProfile())
    val draft: StateFlow<UserProfile> = _draft.asStateFlow()

    private val _blocklist = MutableStateFlow(ProfileBlocklist())
    val blocklist: StateFlow<ProfileBlocklist> = _blocklist.asStateFlow()

    private val _autoUpdateEnabled = MutableStateFlow(true)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    sealed class Event {
        data class Message(val text: String) : Event()
        data object SavedAndExit : Event()
        data object ClearedAndExit : Event()
    }

    suspend fun reload() {
        _loading.value = true
        _error.value = null
        try {
            val profile = loadProfile()
            val visible = profile.copy(
                recentStates = profile.recentStates.filter {
                    !UserProfileEditor.isExpired(it.expiresAt)
                }
            )
            _draft.value = visible
            _blocklist.value = runCatching { loadBlocklist() }.getOrDefault(ProfileBlocklist())
            _autoUpdateEnabled.value = runCatching { loadAutoUpdate() }.getOrDefault(true)
            _dirty.value = false
        } catch (_: Throwable) {
            _draft.value = UserProfile()
            _blocklist.value = ProfileBlocklist()
            _autoUpdateEnabled.value = true
            _error.value = "加载失败，已使用空画像"
            _dirty.value = false
        } finally {
            _loading.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        if (_autoUpdateEnabled.value == enabled) return
        _autoUpdateEnabled.value = enabled
        _dirty.value = true
    }

    fun unblock(category: BlocklistCategory, text: String) {
        _blocklist.value = ProfileBlocklistCodec.unblocked(_blocklist.value, category, text)
        _dirty.value = true
    }

    // —— 文本类 ——
    fun addText(field: Field, raw: String) {
        val current = _draft.value
        UserProfileEditor.addTextItem(field.get(current), raw)
            .onSuccess { updated ->
                val text = UserProfileEditor.normalizeText(raw)
                // 手动添加命中 blocklist → 静默解除阻止
                val cat = field.toBlockCategory()
                if (ProfileBlocklistCodec.isBlocked(cat.getList(_blocklist.value), text)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(_blocklist.value, cat, text)
                }
                _draft.value = field.set(current, updated)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun updateText(field: Field, index: Int, raw: String) {
        val current = _draft.value
        val list = field.get(current)
        if (index !in list.indices) {
            _error.value = "条目不存在"
            return
        }
        val oldValue = list[index]
        UserProfileEditor.updateTextItem(list, index, raw)
            .onSuccess { updated ->
                val newText = UserProfileEditor.normalizeText(raw)
                val cat = field.toBlockCategory()
                // 旧值加入 blocklist
                _blocklist.value = ProfileBlocklistCodec.blocked(_blocklist.value, cat, oldValue)
                // 新值若在 blocklist 则解除
                if (ProfileBlocklistCodec.isBlocked(cat.getList(_blocklist.value), newText)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(_blocklist.value, cat, newText)
                }
                _draft.value = field.set(current, updated)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun removeText(field: Field, index: Int) {
        val current = _draft.value
        val list = field.get(current)
        if (index !in list.indices) return
        val removed = list[index]
        _draft.value = field.set(current, UserProfileEditor.removeAt(list, index))
        _blocklist.value = ProfileBlocklistCodec.blocked(
            _blocklist.value, field.toBlockCategory(), removed
        )
        _dirty.value = true
    }

    // —— 性格 ——
    fun addPersonality(trait: String, confidence: Double) {
        val current = _draft.value
        UserProfileEditor.addPersonality(current.personality, trait, confidence)
            .onSuccess {
                val t = UserProfileEditor.normalizeText(trait)
                if (ProfileBlocklistCodec.isBlocked(_blocklist.value.personalityTraits, t)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(
                        _blocklist.value, BlocklistCategory.PERSONALITY, t
                    )
                }
                _draft.value = current.copy(personality = it)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun updatePersonality(index: Int, trait: String, confidence: Double) {
        val current = _draft.value
        if (index !in current.personality.indices) {
            _error.value = "条目不存在"
            return
        }
        val oldTrait = current.personality[index].trait
        UserProfileEditor.updatePersonality(current.personality, index, trait, confidence)
            .onSuccess {
                val newTrait = UserProfileEditor.normalizeText(trait)
                _blocklist.value = ProfileBlocklistCodec.blocked(
                    _blocklist.value, BlocklistCategory.PERSONALITY, oldTrait
                )
                if (ProfileBlocklistCodec.isBlocked(_blocklist.value.personalityTraits, newTrait)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(
                        _blocklist.value, BlocklistCategory.PERSONALITY, newTrait
                    )
                }
                _draft.value = current.copy(personality = it)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun removePersonality(index: Int) {
        val current = _draft.value
        if (index !in current.personality.indices) return
        val removed = current.personality[index].trait
        _draft.value = current.copy(
            personality = UserProfileEditor.removePersonality(current.personality, index)
        )
        _blocklist.value = ProfileBlocklistCodec.blocked(
            _blocklist.value, BlocklistCategory.PERSONALITY, removed
        )
        _dirty.value = true
    }

    // —— 近期状态 ——
    fun addRecent(content: String, days: Int) {
        val current = _draft.value
        UserProfileEditor.addRecentState(current.recentStates, content, days)
            .onSuccess {
                val text = UserProfileEditor.normalizeText(content)
                if (ProfileBlocklistCodec.isBlocked(_blocklist.value.recentStates, text)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(
                        _blocklist.value, BlocklistCategory.RECENT_STATES, text
                    )
                }
                _draft.value = current.copy(recentStates = it)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun updateRecent(index: Int, content: String, days: Int) {
        val current = _draft.value
        if (index !in current.recentStates.indices) {
            _error.value = "条目不存在"
            return
        }
        val oldContent = current.recentStates[index].content
        UserProfileEditor.updateRecentState(current.recentStates, index, content, days)
            .onSuccess {
                val newText = UserProfileEditor.normalizeText(content)
                _blocklist.value = ProfileBlocklistCodec.blocked(
                    _blocklist.value, BlocklistCategory.RECENT_STATES, oldContent
                )
                if (ProfileBlocklistCodec.isBlocked(_blocklist.value.recentStates, newText)) {
                    _blocklist.value = ProfileBlocklistCodec.unblocked(
                        _blocklist.value, BlocklistCategory.RECENT_STATES, newText
                    )
                }
                _draft.value = current.copy(recentStates = it)
                _dirty.value = true
                _error.value = null
            }
            .onFailure { _error.value = it.message }
    }

    fun removeRecent(index: Int) {
        val current = _draft.value
        if (index !in current.recentStates.indices) return
        val removed = current.recentStates[index].content
        _draft.value = current.copy(
            recentStates = UserProfileEditor.removeRecentState(current.recentStates, index)
        )
        _blocklist.value = ProfileBlocklistCodec.blocked(
            _blocklist.value, BlocklistCategory.RECENT_STATES, removed
        )
        _dirty.value = true
    }

    /**
     * 保存顺序：先 blocklist + autoUpdate，再 profile。
     * 失败方向为「过度阻止」（安全），避免「删了却没阻止」。
     */
    suspend fun save(exitAfter: Boolean = true): Boolean {
        if (_saving.value) return false
        _saving.value = true
        _error.value = null
        val draftSnap = _draft.value
        val blockSnap = _blocklist.value
        val autoSnap = _autoUpdateEnabled.value
        val dirtySnap = _dirty.value
        return try {
            val prepared = UserProfileEditor.prepareForSave(draftSnap)
            // 1) 先持久化保护层
            saveBlocklist(blockSnap)
            saveAutoUpdate(autoSnap)
            // 2) 再持久化画像
            saveProfile(prepared)
            _draft.value = prepared.copy(
                recentStates = prepared.recentStates.filter {
                    !UserProfileEditor.isExpired(it.expiresAt)
                }
            )
            _dirty.value = false
            _events.emit(Event.Message("画像已保存"))
            if (exitAfter) _events.emit(Event.SavedAndExit)
            true
        } catch (e: Throwable) {
            _draft.value = draftSnap
            _blocklist.value = blockSnap
            _autoUpdateEnabled.value = autoSnap
            _dirty.value = dirtySnap
            _error.value = e.message ?: "保存失败"
            false
        } finally {
            _saving.value = false
        }
    }

    /**
     * 清空：当前全部条目加入 blocklist，再写入空画像。
     * 顺序：先 blocklist，再 profile。
     */
    suspend fun clearProfile(): Boolean {
        if (_saving.value) return false
        _saving.value = true
        _error.value = null
        val draftSnap = _draft.value
        val blockSnap = _blocklist.value
        val autoSnap = _autoUpdateEnabled.value
        val dirtySnap = _dirty.value
        return try {
            val blocked = ProfileBlocklistCodec.blockAllFromProfile(draftSnap, blockSnap)
            saveBlocklist(blocked)
            saveAutoUpdate(autoSnap)
            val empty = UserProfileEditor.emptyProfile()
            saveProfile(empty)
            _draft.value = empty
            _blocklist.value = blocked
            _dirty.value = false
            _events.emit(Event.Message("画像已清空"))
            _events.emit(Event.ClearedAndExit)
            true
        } catch (e: Throwable) {
            _draft.value = draftSnap
            _blocklist.value = blockSnap
            _autoUpdateEnabled.value = autoSnap
            _dirty.value = dirtySnap
            _error.value = e.message ?: "清空失败"
            false
        } finally {
            _saving.value = false
        }
    }

    fun markCleanForDiscard() {
        _dirty.value = false
    }

    enum class Field {
        STABLE_FACTS, PREFERENCES, SUPPORT_STYLE, APPEARANCE;

        fun get(p: UserProfile): List<String> = when (this) {
            STABLE_FACTS -> p.stableFacts
            PREFERENCES -> p.preferences
            SUPPORT_STYLE -> p.supportStyle
            APPEARANCE -> p.appearanceFacts
        }

        fun set(p: UserProfile, list: List<String>): UserProfile = when (this) {
            STABLE_FACTS -> p.copy(stableFacts = list)
            PREFERENCES -> p.copy(preferences = list)
            SUPPORT_STYLE -> p.copy(supportStyle = list)
            APPEARANCE -> p.copy(appearanceFacts = list)
        }

        fun toBlockCategory(): BlocklistCategory = when (this) {
            STABLE_FACTS -> BlocklistCategory.STABLE_FACTS
            PREFERENCES -> BlocklistCategory.PREFERENCES
            SUPPORT_STYLE -> BlocklistCategory.SUPPORT_STYLE
            APPEARANCE -> BlocklistCategory.APPEARANCE
        }
    }
}

private fun BlocklistCategory.getList(b: ProfileBlocklist): List<String> = when (this) {
    BlocklistCategory.STABLE_FACTS -> b.stableFacts
    BlocklistCategory.PREFERENCES -> b.preferences
    BlocklistCategory.SUPPORT_STYLE -> b.supportStyle
    BlocklistCategory.APPEARANCE -> b.appearanceFacts
    BlocklistCategory.RECENT_STATES -> b.recentStates
    BlocklistCategory.PERSONALITY -> b.personalityTraits
}
