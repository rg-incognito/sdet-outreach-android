package com.rohitgande.sdetoutreach.pipeline

import android.content.Context
import android.webkit.WebView
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.scraper.LinkedInScraper
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.sheets.appendNewPosts
import com.rohitgande.sdetoutreach.sheets.getExistingPostUrls

data class RunNowResult(val scraped: Int, val withEmail: Int, val postsAdded: Int, val sent: Int)

// The single "Run Now" flow: scrape -> write new posts to the Posts sheet -> send
// outreach for whatever's pending. Equivalent to `npm run run-all`. Shared by the
// foreground RunActivity screen and the background ScrapeAndOutreachWorker so both
// stay in lockstep instead of drifting into two hand-copied orchestrations.
suspend fun runNow(
    webView: WebView,
    scrapeConfig: LinkedInScraper.ScrapeConfig,
    geminiClient: GeminiClient,
    sheetsService: SheetsService,
    context: Context,
    resumeBytes: ByteArray,
    resumeText: String,
    candidate: CandidateConfig,
    dryRun: Boolean,
    onProgress: suspend (String) -> Unit = {},
): RunNowResult {
    onProgress("Checking Posts sheet for known URLs...")
    val existingUrls = getExistingPostUrls(sheetsService)
    onProgress("Posts sheet already has ${existingUrls.size} known URL(s).")

    onProgress("Scraping LinkedIn...")
    val filtered = scrapeToFilteredJobs(webView, scrapeConfig, geminiClient, existingUrls)
    val withEmail = filtered.count { it.recruiterEmail.isNotEmpty() }
    onProgress("Scraped ${filtered.size} post(s), $withEmail with a usable email.")

    val added = appendNewPosts(sheetsService, filtered, existingUrls)
    onProgress("Appended $added new row(s) to the Posts sheet.")

    onProgress("Sending outreach${if (dryRun) " (dry-run)" else ""}...")
    val counts = runSendPipeline(
        geminiClient = geminiClient,
        sheetsService = sheetsService,
        context = context,
        masterResumeText = resumeText,
        resumeBytes = resumeBytes,
        candidate = candidate,
        maxJobsPerRun = scrapeConfig.maxJobsPerRun,
        dryRun = dryRun,
        onProgress = onProgress,
    )

    return RunNowResult(filtered.size, withEmail, added, counts.sent)
}
