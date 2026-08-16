package com.rohitgande.sdetoutreach.data

import android.content.Context
import com.rohitgande.sdetoutreach.BuildConfig
import com.rohitgande.sdetoutreach.pipeline.CandidateConfig
import com.rohitgande.sdetoutreach.scraper.LinkedInScraper
import org.json.JSONArray

// User-editable settings, persisted locally (SharedPreferences) so scrape queries and
// candidate profile fields can be changed on-device without a new APK build. Credentials
// (Gemini/Gmail/Sheets) stay compiled into BuildConfig by design — see CLAUDE.md/session
// notes on that tradeoff. Falls back to the original hardcoded/BuildConfig defaults on
// first run, so an app that's never visited Settings behaves exactly as before.
object Settings {
    private const val PREFS = "nahi_app_settings"
    private const val KEY_QUERIES = "scrape_queries"
    private const val KEY_MAX_JOBS = "max_jobs_per_run"
    private const val KEY_CANDIDATE_NAME = "candidate_name"
    private const val KEY_CANDIDATE_PHONE = "candidate_phone"
    private const val KEY_CANDIDATE_CURRENT_CTC = "candidate_current_ctc"
    private const val KEY_CANDIDATE_EXPECTED_CTC = "candidate_expected_ctc"
    private const val KEY_CANDIDATE_NOTICE_PERIOD = "candidate_notice_period"
    private const val KEY_CANDIDATE_LOCATION = "candidate_location"
    private const val KEY_PRIMARY_RESUME = "primary_resume_filename"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"

    val defaultQueries = listOf(
        "(\"Automation Test Engineer\" OR SDET) AND (\"send resume\" OR \"email resume\" OR \"share your CV\" OR \"mail your CV\")",
        "(\"Automation Test Engineer\" OR SDET) (\"careers@\" OR \"hr@\" OR \"recruitment@\" OR \"talent@\" OR \"jobs@\")",
    )
    const val defaultMaxJobsPerRun = 50

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getQueries(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_QUERIES, null) ?: return defaultQueries
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            defaultQueries
        }
    }

    fun setQueries(context: Context, queries: List<String>) {
        val arr = JSONArray()
        queries.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_QUERIES, arr.toString()).apply()
    }

    fun getMaxJobsPerRun(context: Context): Int =
        prefs(context).getInt(KEY_MAX_JOBS, defaultMaxJobsPerRun)

    fun setMaxJobsPerRun(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_JOBS, value).apply()
    }

    fun getScrapeConfig(context: Context): LinkedInScraper.ScrapeConfig =
        LinkedInScraper.ScrapeConfig(
            queries = getQueries(context),
            maxJobsPerRun = getMaxJobsPerRun(context),
            regionGeoUrn = "",
        )

    data class CandidateProfile(
        val name: String,
        val phone: String,
        val currentCTC: String,
        val expectedCTC: String,
        val noticePeriod: String,
        val location: String,
    )

    fun getCandidateProfile(context: Context): CandidateProfile {
        val p = prefs(context)
        return CandidateProfile(
            name = p.getString(KEY_CANDIDATE_NAME, null) ?: BuildConfig.CANDIDATE_NAME,
            phone = p.getString(KEY_CANDIDATE_PHONE, null) ?: BuildConfig.CANDIDATE_PHONE,
            currentCTC = p.getString(KEY_CANDIDATE_CURRENT_CTC, null) ?: BuildConfig.CANDIDATE_CURRENT_CTC,
            expectedCTC = p.getString(KEY_CANDIDATE_EXPECTED_CTC, null) ?: BuildConfig.CANDIDATE_EXPECTED_CTC,
            noticePeriod = p.getString(KEY_CANDIDATE_NOTICE_PERIOD, null) ?: BuildConfig.CANDIDATE_NOTICE_PERIOD,
            location = p.getString(KEY_CANDIDATE_LOCATION, null) ?: BuildConfig.CANDIDATE_LOCATION,
        )
    }

    fun setCandidateProfile(context: Context, profile: CandidateProfile) {
        prefs(context).edit()
            .putString(KEY_CANDIDATE_NAME, profile.name)
            .putString(KEY_CANDIDATE_PHONE, profile.phone)
            .putString(KEY_CANDIDATE_CURRENT_CTC, profile.currentCTC)
            .putString(KEY_CANDIDATE_EXPECTED_CTC, profile.expectedCTC)
            .putString(KEY_CANDIDATE_NOTICE_PERIOD, profile.noticePeriod)
            .putString(KEY_CANDIDATE_LOCATION, profile.location)
            .apply()
    }

    // gmailUser/gmailAppPassword intentionally always come from BuildConfig, not the
    // editable profile — those are credentials, not identity, and stay build-time secrets.
    fun getCandidateConfig(context: Context): CandidateConfig {
        val profile = getCandidateProfile(context)
        return CandidateConfig(
            name = profile.name,
            phone = profile.phone,
            currentCTC = profile.currentCTC,
            expectedCTC = profile.expectedCTC,
            noticePeriod = profile.noticePeriod,
            location = profile.location,
            gmailUser = BuildConfig.GMAIL_USER,
            gmailAppPassword = BuildConfig.GMAIL_APP_PASSWORD,
        )
    }

    // Explicit user intent, independent of WorkManager's own state — WorkManager can
    // resurrect a previously-cancelled periodic job across an app reinstall/update (its
    // reschedule-on-package-replaced logic doesn't reliably respect a prior cancel). This
    // flag is the actual source of truth: SdetOutreachApp enforces it on every process
    // start by re-cancelling the job whenever this is false, regardless of what
    // WorkManager thinks is currently enqueued.
    fun isBackgroundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND_ENABLED, false)

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
    }

    fun getPrimaryResumeFilename(context: Context): String? =
        prefs(context).getString(KEY_PRIMARY_RESUME, null)

    fun setPrimaryResumeFilename(context: Context, filename: String?) {
        prefs(context).edit().putString(KEY_PRIMARY_RESUME, filename).apply()
    }
}
