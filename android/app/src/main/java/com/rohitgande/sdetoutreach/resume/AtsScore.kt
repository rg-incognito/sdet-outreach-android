package com.rohitgande.sdetoutreach.resume

data class AtsScoreResult(
    val score: Int,
    val matchedKeywords: List<String>,
    val missingKeywords: List<String>,
)

// Direct port of src/resume/atsScore.ts.
private fun tokenize(text: String): Set<String> {
    return text.lowercase()
        .replace(Regex("[^a-z0-9+.#\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .toSet()
}

fun scoreResumeAgainstKeywords(resumeText: String, jdKeywords: List<String>): AtsScoreResult {
    val resumeTokens = tokenize(resumeText)
    val resumeLower = resumeText.lowercase()

    val matched = mutableListOf<String>()
    val missing = mutableListOf<String>()

    for (keyword in jdKeywords) {
        val keywordTokens = keyword.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val isPresent = resumeLower.contains(keyword.lowercase()) ||
            keywordTokens.all { resumeTokens.contains(it) }
        if (isPresent) matched.add(keyword) else missing.add(keyword)
    }

    val score = if (jdKeywords.isEmpty()) 0 else Math.round((matched.size.toDouble() / jdKeywords.size) * 100).toInt()
    return AtsScoreResult(score, matched, missing)
}
