package com.zhiwo.shiguangjian.data.profile

import android.util.Log
import com.google.gson.Gson
import java.util.Locale

/**
 * 用户主动删除/清空后的「禁止 AI 再学习」名单。
 * 存 settings key=userProfileBlocklist 的 JSON。
 * 列表保留原始 trim 文本（UI 展示）；比较用 Locale.ROOT 规范化。
 */
data class ProfileBlocklist(
    val stableFacts: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val supportStyle: List<String> = emptyList(),
    val appearanceFacts: List<String> = emptyList(),
    val recentStates: List<String> = emptyList(),
    val personalityTraits: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        stableFacts.isEmpty() &&
            preferences.isEmpty() &&
            supportStyle.isEmpty() &&
            appearanceFacts.isEmpty() &&
            recentStates.isEmpty() &&
            personalityTraits.isEmpty()

    fun totalCount(): Int =
        stableFacts.size + preferences.size + supportStyle.size +
            appearanceFacts.size + recentStates.size + personalityTraits.size
}

enum class BlocklistCategory {
    STABLE_FACTS,
    PREFERENCES,
    SUPPORT_STYLE,
    APPEARANCE,
    RECENT_STATES,
    PERSONALITY
}

object ProfileBlocklistKeys {
    const val SETTING_KEY = "userProfileBlocklist"
    const val AUTO_UPDATE_KEY = "profileAutoUpdateEnabled"
}

object ProfileBlocklistCodec {
    private const val TAG = "ProfileBlocklist"
    private val gson = Gson()
    const val MAX_PER_CATEGORY = 50

    fun toJson(blocklist: ProfileBlocklist): String = gson.toJson(sanitize(blocklist))

    fun fromJson(raw: String?): ProfileBlocklist {
        if (raw.isNullOrBlank()) return ProfileBlocklist()
        return try {
            sanitize(gson.fromJson(raw, ProfileBlocklist::class.java) ?: ProfileBlocklist())
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "blocklist JSON 损坏，回退空名单: ${e.message}")
            } catch (_: RuntimeException) {
            }
            ProfileBlocklist()
        }
    }

    fun sanitize(blocklist: ProfileBlocklist): ProfileBlocklist =
        ProfileBlocklist(
            stableFacts = cleanList(blocklist.stableFacts),
            preferences = cleanList(blocklist.preferences),
            supportStyle = cleanList(blocklist.supportStyle),
            appearanceFacts = cleanList(blocklist.appearanceFacts),
            recentStates = cleanList(blocklist.recentStates),
            personalityTraits = cleanList(blocklist.personalityTraits)
        )

    /** 规范化 key：trim + Locale.ROOT lowercase */
    fun normalizeKey(text: String): String = text.trim().lowercase(Locale.ROOT)

    fun isBlocked(list: List<String>, text: String): Boolean {
        val key = normalizeKey(text)
        if (key.isEmpty()) return false
        return list.any { normalizeKey(it) == key }
    }

    fun blocked(
        blocklist: ProfileBlocklist,
        category: BlocklistCategory,
        text: String
    ): ProfileBlocklist {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return blocklist
        val current = category.get(blocklist)
        if (isBlocked(current, trimmed)) return blocklist
        // FIFO：追加到末尾，超限丢掉最旧（队首）
        val appended = (current + trimmed).let { list ->
            if (list.size > MAX_PER_CATEGORY) list.takeLast(MAX_PER_CATEGORY) else list
        }
        return category.set(blocklist, appended)
    }

    fun unblocked(
        blocklist: ProfileBlocklist,
        category: BlocklistCategory,
        text: String
    ): ProfileBlocklist {
        val key = normalizeKey(text)
        if (key.isEmpty()) return blocklist
        val filtered = category.get(blocklist).filterNot { normalizeKey(it) == key }
        return category.set(blocklist, filtered)
    }

    fun blockAllFromProfile(profile: UserProfile, base: ProfileBlocklist = ProfileBlocklist()): ProfileBlocklist {
        var result = base
        profile.stableFacts.forEach { result = blocked(result, BlocklistCategory.STABLE_FACTS, it) }
        profile.preferences.forEach { result = blocked(result, BlocklistCategory.PREFERENCES, it) }
        profile.supportStyle.forEach { result = blocked(result, BlocklistCategory.SUPPORT_STYLE, it) }
        profile.appearanceFacts.forEach { result = blocked(result, BlocklistCategory.APPEARANCE, it) }
        profile.recentStates.forEach { result = blocked(result, BlocklistCategory.RECENT_STATES, it.content) }
        profile.personality.forEach { result = blocked(result, BlocklistCategory.PERSONALITY, it.trait) }
        return result
    }

    private fun cleanList(items: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val key = normalizeKey(trimmed)
            if (key in seen) continue
            seen += key
            result += trimmed
            if (result.size >= MAX_PER_CATEGORY) break
        }
        return result
    }
}

/** 从画像中移除被 blocklist 命中的条目（不修改原对象）。 */
fun UserProfile.filterBlocked(blocklist: ProfileBlocklist): UserProfile {
    return copy(
        stableFacts = stableFacts.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.stableFacts, it)
        },
        preferences = preferences.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.preferences, it)
        },
        supportStyle = supportStyle.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.supportStyle, it)
        },
        appearanceFacts = appearanceFacts.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.appearanceFacts, it)
        },
        recentStates = recentStates.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.recentStates, it.content)
        },
        personality = personality.filterNot {
            ProfileBlocklistCodec.isBlocked(blocklist.personalityTraits, it.trait)
        }
    )
}

/**
 * AI 画像更新的确定性应用：
 * - 自动更新关闭 → 完全忽略 AI 更新
 * - 开启 → filterBlocked 后再 mergeWith
 */
fun applyAiProfileUpdate(
    current: UserProfile,
    aiUpdate: UserProfile,
    blocklist: ProfileBlocklist,
    autoUpdateEnabled: Boolean
): UserProfile {
    if (!autoUpdateEnabled) return current
    val filtered = aiUpdate.filterBlocked(blocklist)
    return UserProfileCodec.mergeWith(current, filtered)
}

private fun BlocklistCategory.get(b: ProfileBlocklist): List<String> = when (this) {
    BlocklistCategory.STABLE_FACTS -> b.stableFacts
    BlocklistCategory.PREFERENCES -> b.preferences
    BlocklistCategory.SUPPORT_STYLE -> b.supportStyle
    BlocklistCategory.APPEARANCE -> b.appearanceFacts
    BlocklistCategory.RECENT_STATES -> b.recentStates
    BlocklistCategory.PERSONALITY -> b.personalityTraits
}

private fun BlocklistCategory.set(b: ProfileBlocklist, list: List<String>): ProfileBlocklist = when (this) {
    BlocklistCategory.STABLE_FACTS -> b.copy(stableFacts = list)
    BlocklistCategory.PREFERENCES -> b.copy(preferences = list)
    BlocklistCategory.SUPPORT_STYLE -> b.copy(supportStyle = list)
    BlocklistCategory.APPEARANCE -> b.copy(appearanceFacts = list)
    BlocklistCategory.RECENT_STATES -> b.copy(recentStates = list)
    BlocklistCategory.PERSONALITY -> b.copy(personalityTraits = list)
}
