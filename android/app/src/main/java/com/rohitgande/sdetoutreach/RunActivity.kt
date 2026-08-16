@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.pipeline.runNow
import com.rohitgande.sdetoutreach.resume.ResumeStore
import com.rohitgande.sdetoutreach.sheets.SheetsAuth
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme

// Unified "Run Now" screen — scrape LinkedIn, update the Posts sheet, then send outreach
// for whatever's pending, back to back. Foreground equivalent of `npm run run-all` and of
// what ScrapeAndOutreachWorker does unattended. Keeps a visible WebView (rather than the
// invisible overlay one used in the background) since this always runs with the app open.
class RunActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DRY_RUN = "dry_run"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, true)

        setContent {
            SdetOutreachTheme {
                RunScreen(dryRun = dryRun, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun RunScreen(dryRun: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(true) }
    var succeeded by remember { mutableStateOf<Boolean?>(null) }

    val webView = remember {
        WebView(context).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(Unit) {
        try {
            log.add("Starting run${if (dryRun) " (dry-run)" else ""}...")
            val geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
            val sheetsAuth = SheetsAuth(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY)
            val sheetsService = SheetsService(sheetsAuth, BuildConfig.SHEETS_SPREADSHEET_ID)

            val resumeBytes = ResumeStore.loadPrimaryResumeBytes(context)
            val resumeText = ResumeStore.extractResumeText(resumeBytes)
            val candidate = Settings.getCandidateConfig(context)

            val result = runNow(
                webView = webView,
                scrapeConfig = Settings.getScrapeConfig(context),
                geminiClient = geminiClient,
                sheetsService = sheetsService,
                context = context,
                resumeBytes = resumeBytes,
                resumeText = resumeText,
                candidate = candidate,
                dryRun = dryRun,
                onProgress = { log.add(it) },
            )
            log.add("Done. ${result.scraped} scraped, ${result.withEmail} with an email, ${result.postsAdded} new post(s) added, ${result.sent} email(s) sent.")
            succeeded = true
        } catch (e: Exception) {
            log.add("FAILED: ${e.message}")
            succeeded = false
        } finally {
            running = false
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (dryRun) "Run Now (dry-run)" else "Run Now") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }

            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}
