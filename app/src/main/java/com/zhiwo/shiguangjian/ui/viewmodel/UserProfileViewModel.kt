package com.zhiwo.shiguangjian.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwo.shiguangjian.ZhiwoApplication
import com.zhiwo.shiguangjian.data.profile.BlocklistCategory
import com.zhiwo.shiguangjian.data.profile.ProfileBlocklist
import com.zhiwo.shiguangjian.data.profile.UserProfile
import com.zhiwo.shiguangjian.data.profile.UserProfileEditorSession
import com.zhiwo.shiguangjian.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 画像编辑 ViewModel。
 * 用户保存：直接覆盖 profile + blocklist + autoUpdate（不 mergeWith）。
 */
class UserProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(
        (application as ZhiwoApplication).database.settingDao()
    )

    private val session = UserProfileEditorSession(
        loadProfile = { settingsRepo.getUserProfile() },
        saveProfile = { settingsRepo.saveUserProfile(it) },
        loadBlocklist = { settingsRepo.getProfileBlocklist() },
        saveBlocklist = { settingsRepo.saveProfileBlocklist(it) },
        loadAutoUpdate = { settingsRepo.getProfileAutoUpdateEnabled() },
        saveAutoUpdate = { settingsRepo.setProfileAutoUpdateEnabled(it) }
    )

    val draft: StateFlow<UserProfile> = session.draft
    val blocklist: StateFlow<ProfileBlocklist> = session.blocklist
    val autoUpdateEnabled: StateFlow<Boolean> = session.autoUpdateEnabled
    val loading: StateFlow<Boolean> = session.loading
    val saving: StateFlow<Boolean> = session.saving
    val dirty: StateFlow<Boolean> = session.dirty
    val error: StateFlow<String?> = session.error
    val events: SharedFlow<UserProfileEditorSession.Event> = session.events

    init {
        viewModelScope.launch { session.reload() }
    }

    fun reload() {
        viewModelScope.launch { session.reload() }
    }

    fun clearError() = session.clearError()

    fun setAutoUpdateEnabled(enabled: Boolean) = session.setAutoUpdateEnabled(enabled)

    fun unblock(category: BlocklistCategory, text: String) = session.unblock(category, text)

    fun addText(field: TextField, raw: String) =
        session.addText(field.toSession(), raw)

    fun updateText(field: TextField, index: Int, raw: String) =
        session.updateText(field.toSession(), index, raw)

    fun removeText(field: TextField, index: Int) =
        session.removeText(field.toSession(), index)

    fun addPersonality(trait: String, confidence: Double) =
        session.addPersonality(trait, confidence)

    fun updatePersonality(index: Int, trait: String, confidence: Double) =
        session.updatePersonality(index, trait, confidence)

    fun removePersonality(index: Int) = session.removePersonality(index)

    fun addRecent(content: String, days: Int) = session.addRecent(content, days)

    fun updateRecent(index: Int, content: String, days: Int) =
        session.updateRecent(index, content, days)

    fun removeRecent(index: Int) = session.removeRecent(index)

    fun save(exitAfter: Boolean = true) {
        viewModelScope.launch { session.save(exitAfter) }
    }

    fun clearProfile() {
        viewModelScope.launch { session.clearProfile() }
    }

    fun discardChanges() {
        session.markCleanForDiscard()
        reload()
    }

    enum class TextField {
        STABLE_FACTS, PREFERENCES, SUPPORT_STYLE, APPEARANCE;

        fun toSession(): UserProfileEditorSession.Field = when (this) {
            STABLE_FACTS -> UserProfileEditorSession.Field.STABLE_FACTS
            PREFERENCES -> UserProfileEditorSession.Field.PREFERENCES
            SUPPORT_STYLE -> UserProfileEditorSession.Field.SUPPORT_STYLE
            APPEARANCE -> UserProfileEditorSession.Field.APPEARANCE
        }
    }
}
