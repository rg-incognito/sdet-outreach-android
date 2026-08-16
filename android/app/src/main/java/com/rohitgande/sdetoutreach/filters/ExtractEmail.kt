package com.rohitgande.sdetoutreach.filters

// Direct port of src/filters/extractEmail.ts. mailto-derived emails land on a "Contact:"
// line the scraper appends itself (see LinkedInScraper) — that line is authoritative and
// tried first. The full-text fallback exists for plain-text emails (careers@/hr@/etc.)
// typed as visible text rather than as a mailto link; scanning the raw post body first is
// risky because LinkedIn's rendered text can glue visually-separated DOM nodes together
// with no whitespace between them.
private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
private val CONTACT_LINE_REGEX = Regex("Contact:\\s*(.+)", RegexOption.IGNORE_CASE)

fun extractEmail(descriptionText: String): String? {
    val contactLine = CONTACT_LINE_REGEX.find(descriptionText)
    if (contactLine != null) {
        val match = EMAIL_REGEX.find(contactLine.groupValues[1])
        if (match != null) return match.value
    }
    return EMAIL_REGEX.find(descriptionText)?.value
}
