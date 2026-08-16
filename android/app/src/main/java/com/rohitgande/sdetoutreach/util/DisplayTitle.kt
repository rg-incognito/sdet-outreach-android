package com.rohitgande.sdetoutreach.util

// Direct port of src/utils/displayTitle.ts. LinkedIn content-search results have no
// separate title field — job.title holds the full post body — this derives a short,
// human-readable label for logs, sheet cells, and LLM prompts instead of the raw blob.
fun displayTitle(title: String): String {
    val firstLine = title.substringBefore("\n").trim()
    return if (firstLine.length > 80) "${firstLine.take(80)}…" else firstLine
}
