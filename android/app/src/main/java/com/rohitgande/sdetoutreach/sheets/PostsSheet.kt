package com.rohitgande.sdetoutreach.sheets

import com.rohitgande.sdetoutreach.model.FilteredJob
import com.rohitgande.sdetoutreach.util.displayTitle

// Direct port of src/sheets/postsSheet.ts.
private const val SHEET_TITLE = "Posts"
private val HEADERS = listOf("Scraped At", "Title", "Company", "Recruiter Email", "Description", "Job URL")

data class PostRow(
    val scrapedAt: String,
    val title: String,
    val company: String,
    val recruiterEmail: String,
    val description: String,
    val jobUrl: String,
)

private fun truncate(text: String, max: Int): String = if (text.length > max) text.take(max) else text

suspend fun getExistingPostUrls(service: SheetsService): Set<String> {
    service.getOrCreateSheet(SHEET_TITLE, HEADERS)
    val rows = service.getRows(SHEET_TITLE, HEADERS)
    return rows.mapNotNull { (_, row) -> row["Job URL"]?.trim()?.ifEmpty { null } }.toSet()
}

// Only entries with a non-empty recruiterEmail are worth writing at all — the send step
// can't reach out to a posting it has no address for (matches postsSheet.ts:44 exactly;
// this is NOT the same as the "write every confirmed job, blank email or not" behavior
// upstream in scrapeToPosts.ts — that only decides what to blank, this decides what's
// worth persisting).
suspend fun appendNewPosts(
    service: SheetsService,
    posts: List<FilteredJob>,
    existingUrls: Set<String>? = null,
): Int {
    val withEmail = posts.filter { it.recruiterEmail.isNotEmpty() }
    if (withEmail.isEmpty()) return 0

    service.getOrCreateSheet(SHEET_TITLE, HEADERS)
    val urls = existingUrls ?: getExistingPostUrls(service)

    val newEntries = withEmail.filter { it.job.url !in urls }
    if (newEntries.isEmpty()) return 0

    service.appendRows(
        SHEET_TITLE,
        HEADERS,
        newEntries.map { entry ->
            mapOf(
                "Scraped At" to entry.job.scrapedAt,
                "Title" to displayTitle(entry.job.title),
                "Company" to entry.job.company,
                "Recruiter Email" to entry.recruiterEmail,
                "Description" to truncate(entry.job.descriptionText, 40000),
                "Job URL" to entry.job.url,
            )
        },
    )
    return newEntries.size
}

suspend fun readPosts(service: SheetsService): List<PostRow> {
    service.getOrCreateSheet(SHEET_TITLE, HEADERS)
    val rows = service.getRows(SHEET_TITLE, HEADERS)
    return rows.map { (_, row) ->
        PostRow(
            scrapedAt = row["Scraped At"] ?: "",
            title = row["Title"] ?: "",
            company = row["Company"] ?: "",
            recruiterEmail = row["Recruiter Email"] ?: "",
            description = row["Description"] ?: "",
            jobUrl = row["Job URL"] ?: "",
        )
    }
}
