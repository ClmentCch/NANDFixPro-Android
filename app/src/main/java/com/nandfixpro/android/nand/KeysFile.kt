package com.nandfixpro.android.nand

/**
 * Validates a `prod.keys` file. Only the structure is reported (names, counts,
 * generations); key values are never logged, stored or displayed by the app.
 */
data class ProdKeysSummary(
    val totalLines: Int,
    val validKeys: Int,
    val invalidLines: Int,
    val invalidExamples: List<String>,
    val categories: Map<String, Int>,
    val masterKeys: List<Int>,
    val hasKeyAreaKeys: Boolean,
    val fileName: String,
) {
    val looksComplete: Boolean get() = validKeys >= 100 && masterKeys.isNotEmpty()
    val newestMasterKey: Int? get() = masterKeys.maxOrNull()
}

object ProdKeysParser {

    private val namePattern = Regex("^[a-z0-9_]+$")

    fun parse(text: String, fileName: String = "prod.keys"): ProdKeysSummary {
        var totalLines = 0
        var valid = 0
        val invalid = mutableListOf<String>()
        val categories = linkedMapOf<String, Int>()
        val masters = mutableListOf<Int>()
        var hasKeyArea = false

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            totalLines++
            if (line.startsWith("#") || line.startsWith(";")) return@forEach

            val separator = line.indexOf('=')
            if (separator <= 0) {
                if (invalid.size < 8) invalid += "ligne sans '='"
                return@forEach
            }
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim().replace(" ", "")
            val keyBytes = value.length / 2
            val hexValid = value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" }

            if (!namePattern.matches(name) || !hexValid || keyBytes !in setOf(16, 32, 64)) {
                if (invalid.size < 8) invalid += "entrée invalide: $name"
                return@forEach
            }

            valid++
            val category = name.substringBefore('_')
            categories[category] = (categories[category] ?: 0) + 1

            if (name.startsWith("master_key")) {
                val generation = name.removePrefix("master_key").trimStart('_')
                generation.toIntOrNull(16)?.let { masters += it }
            }
            if (name.startsWith("key_area_key") || name.startsWith("key_area")) {
                hasKeyArea = true
            }
        }

        return ProdKeysSummary(
            totalLines = totalLines,
            validKeys = valid,
            invalidLines = invalid.size,
            invalidExamples = invalid,
            categories = categories.toList().sortedByDescending { it.second }.toMap(),
            masterKeys = masters.distinct().sorted(),
            hasKeyAreaKeys = hasKeyArea,
            fileName = fileName,
        )
    }
}
