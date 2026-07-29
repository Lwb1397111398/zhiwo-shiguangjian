package com.zhiwo.shiguangjian.data.ai

private val gentleReviewSymbols = listOf("🌿", "✨", "✅", "🫶", "💫", "🌱", "☀️")

fun enhanceDailyReviewText(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return text

    val blessingMatch = Regex("🌟\\s*今日寄语").find(text)
    val mainText = blessingMatch?.let { text.substring(0, it.range.first).trim() } ?: text
    val blessingText = blessingMatch?.let { text.substring(it.range.first).trim() } ?: ""

    if (containsGentleSymbol(mainText)) return text
    if (mainText.isBlank()) return text

    val enhancedMain = "${gentleReviewSymbols.first()} $mainText"
    return if (blessingText.isBlank()) {
        enhancedMain
    } else {
        "$enhancedMain\n\n$blessingText"
    }
}

private fun containsGentleSymbol(text: String): Boolean {
    if (gentleReviewSymbols.any { text.contains(it) }) return true
    // 使用码点范围检测常见 emoji，避免错误匹配汉字
    return Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").containsMatchIn(text)
}
