@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.pipeline.runNow
import com.rohitgande.sdetoutreach.resume.ResumeStore
import com.rohitgande.sdetoutreach.sheets.SheetsAuth
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme
import kotlinx.coroutines.launch

// Unified "Run Now" screen — scrape LinkedIn, update the Posts sheet, then send outreach
// for whatever's pending, back to back. Foreground equivalent of `npm run run-all` and of
// what ScrapeAndOutreachWorker does unattended. Keeps a visible WebView (rather than the
// invisible overlay one used in the background) since this always runs with the app open.
//
// Built as a plain LinearLayout with two ComposeViews sandwiching a plain (non-Compose)
// WebView, rather than one Compose tree with the WebView wrapped in AndroidView: on this
// device, a WebView inside Compose's AndroidView never composites to the screen even
// though it works correctly otherwise (loads pages, runs JS, DOM populates normally —
// see LoginActivity's history for the full investigation). A WebView outside Compose's
// view tree renders fine.
class RunActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DRY_RUN = "dry_run"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, true)

        val log = mutableStateListOf<String>()
        var running by mutableStateOf(true)
        var succeeded by mutableStateOf<Boolean?>(null)

        webView = WebView(this).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        val topBar = ComposeView(this).apply {
            setContent {
                SdetOutreachTheme {
                    TopAppBar(
                        title = { Text(if (dryRun) "Run Now (dry-run)" else "Run Now") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                }
            }
        }

        val body = ComposeView(this).apply {
            setContent {
                SdetOutreachTheme {
                    RunBody(log = log, running = running, succeeded = succeeded, onClose = { finish() })
                }
            }
        }

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                topBar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180 * density).toInt()))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        lifecycleScope.launch {
            try {
                log.add("Starting run${if (dryRun) " (dry-run)" else ""}...")
                val geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
                val sheetsAuth = SheetsAuth(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY)
                val sheetsService = SheetsService(sheetsAuth, BuildConfig.SHEETS_SPREADSHEET_ID)

                val resumeBytes = ResumeStore.loadPrimaryResumeBytes(this@RunActivity)
                val resumeText = ResumeStore.extractResumeText(resumeBytes)
                val candidate = Settings.getCandidateConfig(this@RunActivity)

                val result = runNow(
                    webView = webView,
                    scrapeConfig = Settings.getScrapeConfig(this@RunActivity),
                    geminiClient = geminiClient,
                    sheetsService = sheetsService,
                    context = this@RunActivity,
                    resumeBytes = resumeBytes,
                    resumeText = resumeText,
                    candidate = candidate,
                    dryRun = dryRun,
                    onProgress = { log.add(it) },
                )
                log.add(
                    "Done. ${result.scraped} scraped, ${result.withEmail} with an email, " +
                        "${result.postsAdded} new post(s) added, ${result.sent} email(s) sent.",
                )
                succeeded = true
            } catch (e: Exception) {
                log.add("FAILED: ${e.message}")
                succeeded = false
            } finally {
                running = false
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

@Composable
private fun RunBody(log: List<String>, running: Boolean, succeeded: Boolean?, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (succeeded == true) "Run finished." else "Run failed.",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (succeeded == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(log) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!running) {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}
