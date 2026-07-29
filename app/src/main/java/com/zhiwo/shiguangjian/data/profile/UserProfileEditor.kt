package com.zhiwo.shiguangjian.data.profile

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 画像编辑草稿的纯逻辑（可单测，无 Android 依赖）。
 * ViewModel 只负责状态与持久化调用。
 */
object UserProfileEditor {
    const val MAX_TEXT = 100
    const val MAX_LIST = 8
    const val MAX_PERSONALITY = 6
    const val MAX_RECENT = 5
    const val MAX_RECENT_DAYS = 365

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun normalizeText(raw: String): String = raw.trim().take(MAX_TEXT)

    fun isDuplicate(existing: List<String>, candidate: String): Boolean {
        val key = normalizeText(candidate).lowercase(Locale.ROOT)
        if (key.isEmpty()) return false
        return existing.any { it.trim().lowercase(Locale.ROOT) == key }
    }

    fun addTextItem(list: List<String>, raw: String): Result<List<String>> {
        val text = normalizeText(raw)
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        if (list.size >= MAX_LIST) return Result.failure(IllegalArgumentException("该类最多 $MAX_LIST 条"))
        if (isDuplicate(list, text)) return Result.failure(IllegalArgumentException("已存在相同内容"))
        return Result.success(list + text)
    }

    fun updateTextItem(list: List<String>, index: Int, raw: String): Result<List<String>> {
        if (index !in list.indices) return Result.failure(IllegalArgumentException("条目不存在"))
        val text = normalizeText(raw)
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        val others = list.filterIndexed { i, _ -> i != index }
        if (isDuplicate(others, text)) return Result.failure(IllegalArgumentException("已存在相同内容"))
        return Result.success(list.toMutableList().also { it[index] = text })
    }

    fun removeAt(list: List<String>, index: Int): List<String> {
        if (index !in list.indices) return list
        return list.filterIndexed { i, _ -> i != index }
    }

    fun addPersonality(
        list: List<PersonalityTrait>,
        traitRaw: String,
        confidence: Double
    ): Result<List<PersonalityTrait>> {
        val trait = normalizeText(traitRaw)
        if (trait.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        if (list.size >= MAX_PERSONALITY) {
            return Result.failure(IllegalArgumentException("性格倾向最多 $MAX_PERSONALITY 条"))
        }
        if (list.any { it.trait.trim().lowercase(Locale.ROOT) == trait.lowercase(Locale.ROOT) }) {
            return Result.failure(IllegalArgumentException("已存在相同性格描述"))
        }
        val conf = sanitizeConfidence(confidence)
        return Result.success(list + PersonalityTrait(trait, conf))
    }

    fun updatePersonality(
        list: List<PersonalityTrait>,
        index: Int,
        traitRaw: String,
        confidence: Double
    ): Result<List<PersonalityTrait>> {
        if (index !in list.indices) return Result.failure(IllegalArgumentException("条目不存在"))
        val trait = normalizeText(traitRaw)
        if (trait.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        val others = list.filterIndexed { i, _ -> i != index }
        if (others.any { it.trait.trim().lowercase(Locale.ROOT) == trait.lowercase(Locale.ROOT) }) {
            return Result.failure(IllegalArgumentException("已存在相同性格描述"))
        }
        val conf = sanitizeConfidence(confidence)
        return Result.success(list.toMutableList().also {
            it[index] = PersonalityTrait(trait, conf)
        })
    }

    fun removePersonality(list: List<PersonalityTrait>, index: Int): List<PersonalityTrait> {
        if (index !in list.indices) return list
        return list.filterIndexed { i, _ -> i != index }
    }

    fun sanitizeConfidence(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.5
        return value.coerceIn(0.0, 1.0)
    }

    fun confidenceLabel(confidence: Double): String = when {
        confidence >= 0.75 -> "高"
        confidence >= 0.45 -> "中"
        else -> "低"
    }

    /** 固定期限选项：天数 → 到期日 (yyyy-MM-dd) */
    fun expiresAtFromDays(days: Int, today: LocalDate = LocalDate.now()): String {
        val clamped = days.coerceIn(1, MAX_RECENT_DAYS)
        return today.plusDays(clamped.toLong()).format(dateFmt)
    }

    fun addRecentState(
        list: List<RecentState>,
        contentRaw: String,
        days: Int,
        today: LocalDate = LocalDate.now()
    ): Result<List<RecentState>> {
        val content = normalizeText(contentRaw)
        if (content.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        if (list.size >= MAX_RECENT) {
            return Result.failure(IllegalArgumentException("近期状态最多 $MAX_RECENT 条"))
        }
        if (list.any { it.content.trim().lowercase(Locale.ROOT) == content.lowercase(Locale.ROOT) }) {
            return Result.failure(IllegalArgumentException("已存在相同状态"))
        }
        val expiresAt = expiresAtFromDays(days, today)
        return Result.success(list + RecentState(content, expiresAt))
    }

    fun updateRecentState(
        list: List<RecentState>,
        index: Int,
        contentRaw: String,
        days: Int,
        today: LocalDate = LocalDate.now()
    ): Result<List<RecentState>> {
        if (index !in list.indices) return Result.failure(IllegalArgumentException("条目不存在"))
        val content = normalizeText(contentRaw)
        if (content.isEmpty()) return Result.failure(IllegalArgumentException("内容不能为空"))
        val others = list.filterIndexed { i, _ -> i != index }
        if (others.any { it.content.trim().lowercase(Locale.ROOT) == content.lowercase(Locale.ROOT) }) {
            return Result.failure(IllegalArgumentException("已存在相同状态"))
        }
        val expiresAt = expiresAtFromDays(days, today)
        return Result.success(list.toMutableList().also {
            it[index] = RecentState(content, expiresAt)
        })
    }

    fun removeRecentState(list: List<RecentState>, index: Int): List<RecentState> {
        if (index !in list.indices) return list
        return list.filterIndexed { i, _ -> i != index }
    }

    fun isExpired(expiresAt: String, today: LocalDate = LocalDate.now()): Boolean {
        if (expiresAt.isBlank()) return false
        return try {
            LocalDate.parse(expiresAt.take(10), dateFmt).isBefore(today)
        } catch (_: Throwable) {
            false
        }
    }

    /** 保存前清洗：去过期近期状态、限长、sanitize */
    fun prepareForSave(draft: UserProfile, today: LocalDate = LocalDate.now()): UserProfile {
        val activeRecent = draft.recentStates.filter { !isExpired(it.expiresAt, today) }
        val capped = draft.copy(recentStates = activeRecent)
        return UserProfileCodec.sanitize(capped, updatedAt = "")
    }

    fun emptyProfile(): UserProfile = UserProfile()
}
