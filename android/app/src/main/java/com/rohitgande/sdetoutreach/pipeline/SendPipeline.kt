package com.rohitgande.sdetoutreach.pipeline

import android.content.Context
import android.util.Log
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.model.JobPosting
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.sheets.getSentEmails
import com.rohitgande.sdetoutreach.sheets.readPosts
import com.rohitgande.sdetoutreach.util.displayTitle

private const val TAG = "SendPipeline"

data class FilterCounts(val scraped: Int, val hasEmail: Int, var sent: Int)

// Direct port of src/pipeline.ts's runPipeline. Does zero scraping — reads raw postings
// from the Posts sheet (populated separately by ScrapeToPosts.kt), dedups against every
// recruiter email already logged in the Tracker sheet across all time, then tailors and
// sends for the rest. onProgress is an app-only addition (no Node equivalent) for
// streaming status into the UI; it doesn't change the underlying control flow.
suspend fun runSendPipeline(
    geminiClient: GeminiClient,
    sheetsService: SheetsService,
    context: Context,
    masterResumeText: String,
    resumeBytes: ByteArray,
    candidate: CandidateConfig,
    maxJobsPerRun: Int,
    dryRun: Boolean,
    onProgress: suspend (String) -> Unit = {},
): FilterCounts {
    onProgress("Starting run${if (dryRun) " --dry-run" else ""}...")

    val posts = readPosts(sheetsService)
    val sentEmails = getSentEmails(sheetsService)

    val withEmail = posts.filter { it.recruiterEmail.isNotEmpty() }
    val pending = withEmail.filter { it.recruiterEmail.trim().lowercase() !in sentEmails }

    val counts = FilterCounts(scraped = posts.size, hasEmail = withEmail.size, sent = 0)
    onProgress("Posts sheet: ${posts.size} total, ${withEmail.size} with an email, ${pending.size} not yet sent to.")

    if (pending.isEmpty()) {
        onProgress("Nothing new to send this run.")
        return counts
    }

    val capped = pending.take(maxJobsPerRun)
    if (capped.size < pending.size) {
        onProgress("Capping this run to ${capped.size} of ${pending.size} pending row(s) (maxJobsPerRun).")
    }

    // Guards against sending twice in the same run if the Posts sheet has more than one
    // row for the same recruiter email (e.g. they posted more than once).
    val sentThisRun = mutableSetOf<String>()

    for (post in capped) {
        val email = post.recruiterEmail.trim().lowercase()
        if (email in sentThisRun) continue

        val job = JobPosting(
            id = post.jobUrl,
            url = post.jobUrl,
            title = post.title,
            company = post.company,
            descriptionText = post.description,
            scrapedAt = post.scrapedAt,
        )

        try {
            val result = tailorAndSend(
                geminiClient, sheetsService, context, masterResumeText, resumeBytes,
                candidate, job, post.recruiterEmail, dryRun,
            )
            val status = if (result.sent) "SENT" else if (dryRun) "DRY-RUN" else "FAILED"
            onProgress("$status: ${displayTitle(job.title)} @ ${job.company} <${post.recruiterEmail}> (ATS ${result.score})")
            if (result.sent) {
                counts.sent += 1
                sentThisRun.add(email)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process ${displayTitle(job.title)} @ ${job.company}: ${e.message}")
            onProgress("ERROR: ${displayTitle(job.title)} @ ${job.company}: ${e.message}")
        }
    }

    onProgress("Run complete. Sent ${counts.sent} email(s) this run.")
    return counts
}
