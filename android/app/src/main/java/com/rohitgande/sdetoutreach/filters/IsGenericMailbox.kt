package com.rohitgande.sdetoutreach.filters

// Direct port of src/utils/isGenericMailbox.ts's 27-pattern blocklist. Not every email
// found in a post is a recruiter inbox (ADA accommodation-request addresses, internal
// employee-relations mailboxes, etc. turn up in practice).
private val GENERIC_LOCAL_PART_PATTERNS = listOf(
    Regex("^accom+odations?[-.]?request"),
    Regex("^ada$"),
    Regex("^disability"),
    Regex("^employee[-.]?relations?"),
    Regex("^legal$"),
    Regex("^compliance"),
    Regex("^privacy$"),
    Regex("^ethics$"),
    Regex("^no[-.]?reply$"),
    Regex("^do[-.]?not[-.]?reply$"),
    Regex("^webmaster$"),
    Regex("^postmaster$"),
    Regex("^abuse$"),
    Regex("^unsubscribe$"),
    Regex("^bounce"),
    Regex("^security$"),
    Regex("^press$"),
    Regex("^media$"),
    Regex("^pr$"),
    Regex("^billing$"),
    Regex("^accounts?$"),
    Regex("^accounting$"),
    Regex("^finance$"),
    Regex("^sales$"),
    Regex("^support$"),
    Regex("^help(desk)?$"),
    Regex("^customer[-.]?(service|support|care)$"),
)

fun isGenericMailbox(email: String): Boolean {
    val localPart = email.substringBefore("@").lowercase().trim()
    return GENERIC_LOCAL_PART_PATTERNS.any { it.containsMatchIn(localPart) }
}
