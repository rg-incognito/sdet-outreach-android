package com.rohitgande.sdetoutreach.sheets

import android.util.Log

// Direct port of src/sheets/trackerSheet.ts. One row per recruiter email (upserted, not
// appended-per-attempt): a failed attempt updates that email's existing row in place with
// Status "Failed", so the sheet always shows latest state per recruiter and a failed send
// is never silently lost or duplicated on retry.
private const val TAG = "TrackerSheet"
private const val SHEET_TITLE = "Tracker"
private val HEADERS = listOf(
    "Last Updated", "Status", "Job Title", "Company", "Recruiter Email",
    "ATS Score", "Subject", "Body", "Error", "Job URL", "Description",
)

enum class SendStatus { Sent, Failed }

data class TrackerRow(
    val updatedAt: String,
    val status: SendStatus,
    val jobTitle: String,
    val company: String,
    val recruiterEmail: String,
    val atsScore: Int,
    val subject: String,
    val body: String,
    val error: String,
    val jobUrl: String,
    val description: String,
)

private fun truncate(text: String, max: Int): String = if (text.length > max) text.take(max) else text

private fun rowValues(row: TrackerRow): Map<String, Any> = mapOf(
    "Last Updated" to row.updatedAt,
    "Status" to row.status.name,
    "Job Title" to row.jobTitle,
    "Company" to row.company,
    "Recruiter Email" to row.recruiterEmail,
    "ATS Score" to row.atsScore,
    "Subject" to row.subject,
    "Body" to row.body,
    "Error" to row.error,
    "Job URL" to row.jobUrl,
    "Description" to truncate(row.description, 40000),
)

// Best-effort by design, but a failure here is loud (Log.e, not just a warning): the most
// likely cause is a Sheets API error (bad credentials, sheet not shared with the service
// account, quota, network). Since Status now drives retry logic (getSentEmails below), a
// lost write means a real send could be silently repeated next run.
suspend fun upsertTrackerRow(service: SheetsService, row: TrackerRow) {
    try {
        service.getOrCreateSheet(SHEET_TITLE, HEADERS)
        val rows = service.getRows(SHEET_TITLE, HEADERS)

        val targetEmail = row.recruiterEmail.trim().lowercase()
        val existing = rows.find { (_, r) -> (r["Recruiter Email"] ?: "").trim().lowercase() == targetEmail }

        val values = rowValues(row)
        if (existing != null) {
            service.updateRow(SHEET_TITLE, existing.first, HEADERS, values)
        } else {
            service.appendRows(SHEET_TITLE, HEADERS, listOf(values))
        }
    } catch (e: Exception) {
        Log.e(
            TAG,
            "Failed to update tracker row for ${row.recruiterEmail} in the online Tracker sheet " +
                "(is the spreadsheet ID correct, and is it shared with the service account?): ${e.message}. " +
                "This recruiter's send status was NOT recorded - it may be emailed again next run."
        )
    }
}

// Dedup source of truth for the send step: only emails whose latest Tracker row has
// Status "Sent" count as "already sent to" — a row left in "Failed" status is treated as
// still pending, so the next run automatically retries it.
suspend fun getSentEmails(service: SheetsService): Set<String> {
    return try {
        service.getOrCreateSheet(SHEET_TITLE, HEADERS)
        val rows = service.getRows(SHEET_TITLE, HEADERS)

        val emails = mutableSetOf<String>()
        for ((_, row) in rows) {
            val status = (row["Status"] ?: "").trim()
            val email = (row["Recruiter Email"] ?: "").trim().lowercase()
            if (email.isNotEmpty() && status == "Sent") emails.add(email)
        }
        emails
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read tracker sheet for dedup: ${e.message}")
        emptySet()
    }
}
