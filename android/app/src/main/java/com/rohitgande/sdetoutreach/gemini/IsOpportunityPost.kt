package com.rohitgande.sdetoutreach.gemini

// Direct port of src/gemini/isOpportunityPost.ts — exact prompt, 3000-char truncation,
// same "^\s*yes\b" (case-insensitive) response check. Caller is responsible for the
// fail-open behavior on exception (keep the post rather than drop it) — this function
// itself throws straight through on any Gemini failure, matching the Node original.
suspend fun isOpportunityPost(client: GeminiClient, descriptionText: String): Boolean {
    val prompt = """
        You are screening a LinkedIn post before a job candidate emails their resume to a contact address found in it. Decide whether this post is someone (a recruiter, hiring manager, or company) directly offering a job opening and inviting candidates to apply, send their resume, or email them for that role.

        Answer NO if the post is any of these instead:
        - A candidate/job-seeker themselves posting that THEY are looking for a job or open to work
        - Someone asking their own network to refer them or share job openings with them
        - Someone reposting, sharing, or commenting on someone else's job posting without themselves being the hiring contact
        - General career advice, commentary, or an unrelated post that merely happens to contain an email address

        Respond with ONLY the single word YES or NO, nothing else.

        Post:
        ${descriptionText.take(3000)}
    """.trimIndent()

    val raw = client.generateText(prompt)
    return Regex("^\\s*yes\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
}
