package com.rohitgande.sdetoutreach.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rohitgande.sdetoutreach.BuildConfig
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.pipeline.runNow
import com.rohitgande.sdetoutreach.resume.ResumeStore
import com.rohitgande.sdetoutreach.scraper.OverlayWebViewHost
import com.rohitgande.sdetoutreach.sheets.SheetsAuth
import com.rohitgande.sdetoutreach.sheets.SheetsService

private const val TAG = "ScrapeAndOutreachWorker"
private const val CHANNEL_ID = "sdet_outreach_run"
private const val NOTIFICATION_ID = 1001

// The unattended background equivalent of `npm run run-all` (scrape -> Posts sheet ->
// Gemini/Sheets/Gmail send), run as a WorkManager CoroutineWorker under
// setForegroundAsync so it survives Doze/background limits for its multi-minute
// duration. Scraping uses an invisible overlay-window WebView (OverlayWebViewHost) since
// this runs with no Activity in the foreground.
class ScrapeAndOutreachWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo("Starting run..."))

        val overlayHost = OverlayWebViewHost(applicationContext)
        if (!overlayHost.isOverlayPermissionGranted()) {
            Log.w(TAG, "\"Display over other apps\" not granted — cannot run the background scrape. Grant it from the app's Background settings screen.")
            return Result.failure()
        }

        return try {
            val geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
            val sheetsAuth = SheetsAuth(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY)
            val sheetsService = SheetsService(sheetsAuth, BuildConfig.SHEETS_SPREADSHEET_ID)

            val resumeBytes = ResumeStore.loadPrimaryResumeBytes(applicationContext)
            val resumeText = ResumeStore.extractResumeText(resumeBytes)
            val candidate = Settings.getCandidateConfig(applicationContext)

            val webView = overlayHost.create()
            val result = try {
                runNow(
                    webView = webView,
                    scrapeConfig = Settings.getScrapeConfig(applicationContext),
                    geminiClient = geminiClient,
                    sheetsService = sheetsService,
                    context = applicationContext,
                    resumeBytes = resumeBytes,
                    resumeText = resumeText,
                    candidate = candidate,
                    dryRun = false,
                    onProgress = {
                        Log.i(TAG, it)
                        setForeground(foregroundInfo(it))
                    },
                )
            } finally {
                overlayHost.destroy()
            }
            Log.i(TAG, "Run complete. Sent ${result.sent} email(s) this run.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background run failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun foregroundInfo(status: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Outreach runs", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Nahi App")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
