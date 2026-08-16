package com.rohitgande.sdetoutreach.gemini

import org.json.JSONArray

// Direct port of src/gemini/extractSkills.ts.
suspend fun extractSkills(client: GeminiClient, jobDescription: String): List<String> {
    val prompt = """
        Extract the 10-20 most important skill/technology/requirement keywords from this job description. Respond with ONLY a JSON array of lowercase strings, no other text, no markdown code fences.

        Job description:
        $jobDescription
    """.trimIndent()

    // Unlike JSON-parse failures below, a generateText() failure here is NOT caught —
    // it propagates to the caller, matching src/gemini/extractSkills.ts exactly (the
    // Node original only wraps parsing, not the model call itself).
    val raw = client.generateText(prompt)

    val match = Regex("\\[[\\s\\S]*\\]").find(raw) ?: return emptyList()
    return try {
        val array = JSONArray(match.value)
        (0 until array.length()).mapNotNull { i -> array.opt(i) as? String }
    } catch (e: Exception) {
        emptyList()
    }
}
