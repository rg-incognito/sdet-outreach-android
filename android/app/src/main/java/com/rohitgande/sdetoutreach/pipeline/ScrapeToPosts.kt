package com.rohitgande.sdetoutreach.pipeline

import android.util.Log
import android.webkit.WebView
import com.rohitgande.sdetoutreach.filters.extractEmail
import com.rohitgande.sdetoutreach.filters.isGenericMailbox
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.gemini.isOpportunityPost
import com.rohitgande.sdetoutreach.model.FilteredJob
import com.rohitgande.sdetoutreach.scraper.LinkedInScraper

private const val TAG = "ScrapeToPosts"

// Port of src/scrapeToPosts.ts, minus the Posts-sheet dedup/write step (added around
// this result in Phase 4 — see PostsSheet.kt). Order matches exactly: scrape -> email
// extraction + generic-mailbox filter (blank email, keep the row) -> Gemini opportunity
// screen (only called when an email is present; fail-open on error, keeping the email
// rather than dropping a possibly-genuine lead).
suspend fun scrapeToFilteredJobs(
    webView: WebView,
    scrapeConfig: LinkedInScraper.ScrapeConfig,
    geminiClient: GeminiClient,
    existingUrls: Set<String> = emptySet(),
): List<FilteredJob> {
    val rawJobs = LinkedInScraper.scrape(webView, scrapeConfig)
    val jobs = rawJobs.filter { it.url !in existingUrls }

    var genericSkipped = 0
    val withEmails = jobs.map { job ->
        val email = extractEmail(job.descriptionText) ?: ""
        if (email.isNotEmpty() && isGenericMailbox(email)) {
            genericSkipped++
            FilteredJob(job, "")
        } else {
            FilteredJob(job, email)
        }
    }

    var notOpportunitySkipped = 0
    val confirmed = mutableListOf<FilteredJob>()
    for (entry in withEmails) {
        if (entry.recruiterEmail.isEmpty()) {
            confirmed.add(entry)
            continue
        }
        try {
            val opportunity = isOpportunityPost(geminiClient, entry.job.descriptionText)
            if (!opportunity) {
                notOpportunitySkipped++
                confirmed.add(FilteredJob(entry.job, ""))
                continue
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opportunity-context check failed for ${entry.job.url}, keeping it: ${e.message}")
        }
        confirmed.add(entry)
    }

    val withEmailCount = confirmed.count { it.recruiterEmail.isNotEmpty() }
    Log.i(
        TAG,
        "${confirmed.size} job(s), $withEmailCount with a usable email " +
            "(generic mailbox filtered: $genericSkipped, not-an-opportunity filtered: $notOpportunitySkipped)"
    )

    return confirmed
}
