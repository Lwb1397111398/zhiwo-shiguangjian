package com.zhiwo.shiguangjian.data.repository

import com.zhiwo.shiguangjian.data.ai.DateFormats
import com.zhiwo.shiguangjian.data.db.dao.SettingDao
import com.zhiwo.shiguangjian.data.db.entity.SettingEntity
import com.zhiwo.shiguangjian.data.profile.ProfileBlocklist
import com.zhiwo.shiguangjian.data.profile.ProfileBlocklistCodec
import com.zhiwo.shiguangjian.data.profile.ProfileBlocklistKeys
import com.zhiwo.shiguangjian.data.profile.UserProfile
import com.zhiwo.shiguangjian.data.profile.UserProfileCodec
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val settingDao: SettingDao) {

    fun getAllSettings(): Flow<List<SettingEntity>> = settingDao.getAllSettings()

    suspend fun getSetting(key: String): String? = settingDao.getSettingValue(key)

    suspend fun setSetting(key: String, value: String) {
        settingDao.insertSetting(SettingEntity(key, value))
    }

    suspend fun deleteSetting(key: String) = settingDao.deleteSettingByKey(key)

    suspend fun getUserProfile(): UserProfile {
        val raw = getSetting(SETTING_KEY)
        return UserProfileCodec.fromJson(raw)
    }

    /**
     * 直接覆盖 settings 中的 userProfile（不 mergeWith）。
     * 用户编辑与 AI 路径（AI 侧先 merge 再调用）共用此方法。
     */
    suspend fun saveUserProfile(profile: UserProfile) {
        val sanitized = UserProfileCodec.sanitize(
            profile,
            updatedAt = DateFormats.nowDateTimeIso()
        )
        setSetting(SETTING_KEY, UserProfileCodec.toJson(sanitized))
    }

    suspend fun getProfileBlocklist(): ProfileBlocklist {
        val raw = getSetting(ProfileBlocklistKeys.SETTING_KEY)
        return ProfileBlocklistCodec.fromJson(raw)
    }

    suspend fun saveProfileBlocklist(blocklist: ProfileBlocklist) {
        setSetting(
            ProfileBlocklistKeys.SETTING_KEY,
            ProfileBlocklistCodec.toJson(blocklist)
        )
    }

    /** 缺失时默认 true（保持既有自动更新行为）。 */
    suspend fun getProfileAutoUpdateEnabled(): Boolean {
        val raw = getSetting(ProfileBlocklistKeys.AUTO_UPDATE_KEY)
        return raw != "false"
    }

    suspend fun setProfileAutoUpdateEnabled(enabled: Boolean) {
        setSetting(ProfileBlocklistKeys.AUTO_UPDATE_KEY, if (enabled) "true" else "false")
    }

    companion object {
        private const val SETTING_KEY = "userProfile"
    }
}
