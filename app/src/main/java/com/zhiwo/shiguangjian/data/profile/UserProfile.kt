package com.zhiwo.shiguangjian.data.profile

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zhiwo.shiguangjian.data.ai.AiJsonParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 轻量用户画像，存入 settings 表 key=userProfile 的 JSON。
 */
data class UserProfile(
    val schemaVersion: Int = 1,
    val stableFacts: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val supportStyle: List<String> = emptyList(),
    val personality: List<PersonalityTrait> = emptyList(),
    val appearanceFacts: List<String> = emptyList(),
    val recentStates: List<RecentState> = emptyList(),
    val updatedAt: String = ""
) {
    fun isEmpty(): Boolean =
        stableFacts.isEmpty() &&
            preferences.isEmpty() &&
            supportStyle.isEmpty() &&
            personality.isEmpty() &&
            appearanceFacts.isEmpty() &&
            recentStates.isEmpty()
}

data class PersonalityTrait(
    val trait: String = "",
    val confidence: Double = 0.0
)

data class RecentState(
    val content: String = "",
    val expiresAt: String = ""
)

data class MemoryExtractionResult(
    val newMemories: List<String> = emptyList(),
    val updatedProfile: UserProfile? = null
)

object UserProfileKeys {
    const val SETTING_KEY = "userProfile"
}

object UserProfileCodec {
    private const val TAG = "UserProfile"
    private val gson = Gson()
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private const val MAX_LIST = 8
    private const val MAX_PERSONALITY = 6
    private const val MAX_RECENT = 5
    private const val MAX_SUMMARY_CHARS = 500

    fun toJson(profile: UserProfile): String = gson.toJson(profile)

    fun fromJson(raw: String?): UserProfile {
        if (raw.isNullOrBlank()) return UserProfile()
        return try {
            val parsed = gson.fromJson(raw, UserProfile::class.java) ?: UserProfile()
            sanitize(parsed, updatedAt = parsed.updatedAt)
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "画像 JSON 损坏，回退空画像: ${e.message}")
            } catch (_: RuntimeException) {
            }
            UserProfile()
        }
    }

    fun sanitize(profile: UserProfile, updatedAt: String = ""): UserProfile {
        return UserProfile(
            schemaVersion = if (profile.schemaVersion <= 0) 1 else profile.schemaVersion,
            stableFacts = cleanStringList(profile.stableFacts, MAX_LIST),
            preferences = cleanStringList(profile.preferences, MAX_LIST),
            supportStyle = cleanStringList(profile.supportStyle, MAX_LIST),
            personality = cleanPersonality(profile.personality),
            appearanceFacts = cleanStringList(profile.appearanceFacts, MAX_LIST),
            recentStates = cleanRecentStates(profile.recentStates),
            updatedAt = updatedAt.ifBlank { profile.updatedAt }
        )
    }

    fun toPromptSummary(profile: UserProfile, maxChars: Int = MAX_SUMMARY_CHARS): String {
        if (profile.isEmpty()) return "暂无可靠画像"

        val today = LocalDate.now()
        val lines = mutableListOf<String>()

        if (profile.preferences.isNotEmpty()) lines += "偏好：${profile.preferences.joinToString("、")}"
        if (profile.supportStyle.isNotEmpty()) lines += "沟通方式：${profile.supportStyle.joinToString("、")}"
        if (profile.stableFacts.isNotEmpty()) lines += "稳定事实：${profile.stableFacts.joinToString("、")}"
        val activePersonality = profile.personality.filter { it.trait.isNotBlank() }
        if (activePersonality.isNotEmpty()) {
            val text = activePersonality.joinToString("、") { trait ->
                val level = confidenceLabel(trait.confidence)
                "${trait.trait}（$level）"
            }
            lines += "性格倾向：$text"
        }
        if (profile.appearanceFacts.isNotEmpty()) lines += "外貌自述：${profile.appearanceFacts.joinToString("、")}"
        val activeStates = profile.recentStates.filter { state ->
            state.content.isNotBlank() && !isExpired(state.expiresAt, today)
        }
        if (activeStates.isNotEmpty()) {
            lines += "近期状态：${activeStates.joinToString("、") { it.content }}"
        }

        if (lines.isEmpty()) return "暂无可靠画像"

        var summary = lines.joinToString("\n")
        if (summary.length > maxChars) {
            summary = summary.take(maxChars).trimEnd() + "…"
        }
        return summary
    }

    fun mergeWith(base: UserProfile, update: UserProfile): UserProfile {
        return UserProfile(
            schemaVersion = update.schemaVersion,
            stableFacts = mergeStringList(base.stableFacts, update.stableFacts, MAX_LIST),
            preferences = mergeStringList(base.preferences, update.preferences, MAX_LIST),
            supportStyle = mergeStringList(base.supportStyle, update.supportStyle, MAX_LIST),
            personality = mergePersonality(base.personality, update.personality),
            appearanceFacts = mergeStringList(base.appearanceFacts, update.appearanceFacts, MAX_LIST),
            recentStates = mergeRecentStates(base.recentStates, update.recentStates),
            updatedAt = com.zhiwo.shiguangjian.data.ai.DateFormats.nowDateTimeIso()
        )
    }

    private fun mergeStringList(base: List<String>, update: List<String>, max: Int): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        for (item in base + update) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase(Locale.ROOT)
            if (key in seen) continue
            seen += key
            result += trimmed
            if (result.size >= max) break
        }
        return result
    }

    private fun mergePersonality(base: List<PersonalityTrait>, update: List<PersonalityTrait>): List<PersonalityTrait> {
        val result = mutableListOf<PersonalityTrait>()
        val confByKey = linkedMapOf<String, Double>()
        fun put(trait: String, confidence: Double) {
            val key = trait.lowercase(Locale.ROOT)
            val conf = confidence.coerceIn(0.0, 1.0)
            val old = confByKey[key]
            if (old == null || conf >= old) {
                confByKey[key] = conf
                result.removeAll { it.trait.equals(trait, ignoreCase = true) }
                result += PersonalityTrait(trait, conf)
            }
        }
        for (item in base) {
            val trait = item.trait.trim()
            if (trait.isEmpty()) continue
            put(trait, item.confidence)
            if (result.size >= MAX_PERSONALITY) break
        }
        for (item in update) {
            val trait = item.trait.trim()
            if (trait.isEmpty()) continue
            put(trait, item.confidence)
            if (result.size >= MAX_PERSONALITY) break
        }
        return result.take(MAX_PERSONALITY)
    }

    private fun mergeRecentStates(base: List<RecentState>, update: List<RecentState>): List<RecentState> {
        val byKey = linkedMapOf<String, RecentState>()
        fun put(item: RecentState) {
            val content = item.content.trim()
            if (content.isEmpty()) return
            val key = content.lowercase(Locale.ROOT)
            val existing = byKey[key]
            if (existing == null || item.expiresAt >= existing.expiresAt) {
                byKey[key] = RecentState(content, item.expiresAt.trim())
            }
        }
        base.forEach(::put)
        update.forEach(::put)
        return byKey.values.take(MAX_RECENT)
    }

    private const val MAX_ITEM_CHARS = 100

    private fun cleanStringList(items: List<String>, max: Int): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase(Locale.ROOT)
            if (key in seen) continue
            seen += key
            result += trimmed.take(MAX_ITEM_CHARS)
            if (result.size >= max) break
        }
        return result
    }

    private fun cleanPersonality(items: List<PersonalityTrait>): List<PersonalityTrait> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<PersonalityTrait>()
        for (item in items) {
            val trait = item.trait.trim()
            if (trait.isEmpty()) continue
            val key = trait.lowercase(Locale.ROOT)
            if (key in seen) continue
            seen += key
            val conf = item.confidence
            if (conf.isNaN() || conf.isInfinite()) continue
            result += PersonalityTrait(trait.take(MAX_ITEM_CHARS), conf.coerceIn(0.0, 1.0))
            if (result.size >= MAX_PERSONALITY) break
        }
        return result
    }

    private fun cleanRecentStates(items: List<RecentState>): List<RecentState> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<RecentState>()
        for (item in items) {
            val content = item.content.trim()
            if (content.isEmpty()) continue
            val key = content.lowercase(Locale.ROOT)
            if (key in seen) continue
            seen += key
            result += RecentState(content.take(MAX_ITEM_CHARS), item.expiresAt.trim())
            if (result.size >= MAX_RECENT) break
        }
        return result
    }

    private fun confidenceLabel(confidence: Double): String = when {
        confidence >= 0.75 -> "较高置信度"
        confidence >= 0.45 -> "中等置信度"
        else -> "较低置信度"
    }

    private fun isExpired(expiresAt: String, today: LocalDate): Boolean {
        if (expiresAt.isBlank()) return false
        return try {
            val datePart = expiresAt.take(10)
            val expiry = LocalDate.parse(datePart, dateFormatter)
            expiry.isBefore(today)
        } catch (_: Throwable) {
            false
        }
    }

    private fun logError(message: String) {
        try {
            Log.e(TAG, message)
        } catch (_: RuntimeException) {
        }
    }
}

object MemoryExtractionParser {
    private const val MAX_NEW_MEMORIES = 2

    fun parse(raw: String): MemoryExtractionResult {
        if (raw.isBlank()) return MemoryExtractionResult()

        // 1) 优先新对象格式
        try {
            val obj = AiJsonParser.parseObject(raw)
            val memories = parseNewMemories(obj)
            val profile = parseUpdatedProfile(obj)
            return MemoryExtractionResult(newMemories = memories, updatedProfile = profile)
        } catch (_: Throwable) {
        }

        // 2) 旧 JSON 数组回退
        try {
            val arr = AiJsonParser.parseArray(raw)
            val memories = arr.mapNotNull {
                runCatching { it.asString.trim() }.getOrNull()
            }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(MAX_NEW_MEMORIES)
            return MemoryExtractionResult(newMemories = memories, updatedProfile = null)
        } catch (_: Throwable) {
        }

        return MemoryExtractionResult()
    }

    private fun parseNewMemories(obj: JsonObject): List<String> {
        return try {
            val arr = obj.getAsJsonArray("newMemories") ?: return emptyList()
            arr.mapNotNull {
                runCatching { it.asString.trim() }.getOrNull()
            }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(MAX_NEW_MEMORIES)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseUpdatedProfile(obj: JsonObject): UserProfile? {
        return try {
            if (!obj.has("updatedProfile") || obj.get("updatedProfile").isJsonNull) {
                return null
            }
            val profileElement = obj.get("updatedProfile")
            if (!profileElement.isJsonObject) return null
            val raw = Gson().fromJson(profileElement, UserProfile::class.java) ?: return null
            UserProfileCodec.sanitize(raw, updatedAt = "")
        } catch (_: Throwable) {
            null
        }
    }
}