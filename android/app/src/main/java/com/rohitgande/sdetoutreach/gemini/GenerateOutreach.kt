package com.rohitgande.sdetoutreach.gemini

import com.rohitgande.sdetoutreach.model.JobPosting
import org.json.JSONObject

data class OutreachEmail(val subject: String, val body: String)

// Direct port of src/gemini/generateOutreach.ts — prompt text, truncation lengths (1500
// JD chars / 4000 resume chars), sign-off branching, and the conditional CTC/notice
// period/location block are all reproduced verbatim. The "only include a detail if the
// post explicitly asks for it" decision is entirely prompt-driven (Gemini reads the post
// excerpt itself) — there is no separate deterministic keyword check here, matching the
// Node original.
suspend fun generateOutreach(
    client: GeminiClient,
    job: JobPosting,
    matchedKeywords: List<String>,
    candidateName: String,
    candidatePhone: String,
    roleTitle: String,
    masterResume: String,
    candidateCurrentCTC: String,
    candidateExpectedCTC: String,
    candidateNoticePeriod: String,
    candidateLocation: String,
): OutreachEmail {
    val fallbackSubject = "$roleTitle — $candidateName"

    val signOffInstruction = if (candidatePhone.isNotEmpty()) {
        "Sign off with the candidate's name \"$candidateName\" on one line, then their phone number \"$candidatePhone\" on the next line — exactly those two lines, nothing else after (no email address or other contact field; the phone number is the only extra contact detail to include)."
    } else {
        "Sign off as \"$candidateName\" only — no phone number, email address, or any other contact field after the name; the message itself is the contact method."
    }

    val availableDetails = mutableListOf<String>()
    if (candidateCurrentCTC.isNotEmpty()) availableDetails.add("Current CTC: $candidateCurrentCTC")
    if (candidateExpectedCTC.isNotEmpty()) availableDetails.add("Expected CTC: $candidateExpectedCTC")
    if (candidateNoticePeriod.isNotEmpty()) availableDetails.add("Notice Period: $candidateNoticePeriod")
    if (candidateLocation.isNotEmpty()) availableDetails.add("Current Location: $candidateLocation")

    val detailsInstruction = if (availableDetails.isNotEmpty()) {
        "The candidate has these details available, but they are NOT all relevant to every post:\n" +
            availableDetails.joinToString("\n") +
            "\nRead the post excerpt below carefully. Only include a \"Label: value\" line for a detail above if the post/job description explicitly asks applicants to share that specific piece of information (e.g. it asks for current CTC, expected CTC, notice period, or current location/city). For each detail the post explicitly asks for, include it as its own line before the closing sign-off, copied verbatim from the list above with no rewording, rounding, or reformatting. Do not include any line for a detail the post did not ask for, and do not include the block at all if the post didn't ask for any of them."
    } else {
        "Do not include a CTC, expected CTC, notice period, or location block — none of those values were provided."
    }

    val prompt = """
        Write a short, professional cold outreach email from a job candidate about a specific LinkedIn post. Keep it under 150 words, 2-3 short paragraphs, no generic filler. This post either explicitly asked candidates to send/email their resume or CV, or listed a recruiting contact address - write as if reaching out directly to that contact with genuine interest in the role, mentioning that your resume is attached. Reference the exact role title and company, and naturally mention 2-3 of the candidate's relevant skills from this list: ${matchedKeywords.joinToString(", ").ifEmpty { "(none extracted)" }}. End with a polite call to action. $signOffInstruction

        Absolute rule, applies to the whole email including the subject: NEVER write a bracketed or angle-bracketed placeholder anywhere — no "[Recruiter Name]", "[Hiring Manager]", "[your email address]", "[your phone number]", "[Company]", "[Insert Current CTC]", or anything similar. Every one of those reads as broken, unfilled mail-merge text and is worse than just leaving it out. If a detail isn't known (e.g. the recruiter's name), don't reference it at all — write around it naturally instead.

        $detailsInstruction

        Every factual claim about the candidate — years of experience, job titles held, specific skills, tools, or achievements — must be explicitly grounded in the candidate resume text given below. Do not invent, round up, estimate, or infer any number or fact that is not literally stated in that resume (for example, never state a specific total years-of-experience figure unless that exact figure appears in the resume text — including one drawn from the job post's requirements instead of the candidate's actual background). If a detail the job post emphasizes isn't verifiable from the resume, write around it in general terms rather than asserting it as fact.

        Greeting: we do not know the recruiter's actual name, so greet naturally without inventing one, e.g. "Hi there," or "Hello," or address the company directly, e.g. "Hi <actual company name> team,". Pick whichever reads most natural for this specific message.

        Subject line: write it like a real person would, not a templated auto-generated one. Avoid rigid patterns like "Inquiry: <Role> - <Company>", "Application for <Role> at <Company>", or "Interest in <Role> Role" — these read as cheap and robotic. Instead make it sound like a specific, individual email about this specific role, while staying short and professional.

        Formatting: this is sent as a plain-text email, not HTML or markdown. Do not use **bold**, bullet points, headers, or any other markdown syntax — plain sentences and paragraphs only.

        Respond as JSON only, no markdown code fences: {"subject": "...", "body": "..."}

        Role: $roleTitle
        Company: ${job.company}
        Post excerpt: ${job.descriptionText.take(1500)}

        Candidate resume (the only source of truth for any fact about the candidate):
        ${masterResume.take(4000)}
    """.trimIndent()

    val raw = client.generateText(prompt)

    val match = Regex("\\{[\\s\\S]*\\}").find(raw)
        ?: return OutreachEmail(fallbackSubject, raw)

    return try {
        val parsed = JSONObject(match.value)
        val subject = (parsed.opt("subject") as? String) ?: fallbackSubject
        val body = (parsed.opt("body") as? String) ?: raw
        OutreachEmail(subject, body)
    } catch (e: Exception) {
        OutreachEmail(fallbackSubject, raw)
    }
}
