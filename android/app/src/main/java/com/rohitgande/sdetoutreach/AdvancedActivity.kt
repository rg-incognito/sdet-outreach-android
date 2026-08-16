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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.mail.OutgoingEmail
import com.rohitgande.sdetoutreach.mail.sendMail
import com.rohitgande.sdetoutreach.pipeline.runSendPipeline
import com.rohitgande.sdetoutreach.pipeline.scrapeToFilteredJobs
import com.rohitgande.sdetoutreach.resume.ResumeStore
import com.rohitgande.sdetoutreach.sheets.SheetsAuth
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.sheets.appendNewPosts
import com.rohitgande.sdetoutreach.sheets.getExistingPostUrls
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme
import kotlinx.coroutines.launch
import java.time.Instant

// Debug tools that exercise one half of the pipeline at a time — useful when something
// upstream (Sheets, Gemini, SMTP) needs isolating from the rest. Ordinary use is
// MainActivity's single "Run Now" button; these three are for troubleshooting only.
class AdvancedActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdetOutreachTheme {
                AdvancedScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AdvancedScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScrapeOnlyCard()
            SendOnlyCard()
            SmtpTestCard()
        }
    }
}

@Composable
private fun ToolCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun ScrapeOnlyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val webView = remember {
        WebView(context).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    ToolCard(
        title = "Scrape only",
        description = "Scrapes LinkedIn, screens results with Gemini, and writes new rows to the Posts sheet. Does not send any email.",
    ) {
        Card(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
        Button(
            onClick = {
                running = true
                log = ""
                scope.launch {
                    log = runScrapeOnly(context, webView) { line -> log += "$line\n" }
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Running..." else "Run scrape")
        }
        if (log.isNotEmpty()) LogText(log)
    }
}

private suspend fun runScrapeOnly(context: android.content.Context, webView: WebView, onLine: (String) -> Unit): String {
    val log = StringBuilder()
    fun append(line: String) {
        log.append(line).append("\n")
        onLine(line)
    }
    try {
        val geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
        val sheetsAuth = SheetsAuth(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY)
        val sheetsService = SheetsService(sheetsAuth, BuildConfig.SHEETS_SPREADSHEET_ID)
        val existingUrls = getExistingPostUrls(sheetsService)
        append("Posts sheet already has ${existingUrls.size} known URL(s).")

        val filtered = scrapeToFilteredJobs(webView, Settings.getScrapeConfig(context), geminiClient, existingUrls)
        val withEmail = filtered.count { it.recruiterEmail.isNotEmpty() }
        append("Scraped ${filtered.size} post(s), $withEmail with a usable email.")

        val added = appendNewPosts(sheetsService, filtered, existingUrls)
        append("Appended $added new row(s) to the Posts sheet.")
    } catch (e: Exception) {
        append("FAILED: ${e.message}")
    }
    return log.toString()
}

@Composable
private fun SendOnlyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf("") }

    ToolCard(
        title = "Send only",
        description = "Reads the Posts sheet, skips anyone already marked Sent in the Tracker, and emails the rest. Does not scrape.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dryRun, onCheckedChange = { dryRun = it }, enabled = !running)
            Text(if (dryRun) "Dry run" else "Live — sends real email", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = {
                running = true
                log = ""
                scope.launch {
                    log = runSendOnly(context, dryRun) { line -> log += "$line\n" }
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Running..." else "Run send")
        }
        if (log.isNotEmpty()) LogText(log)
    }
}

private suspend fun runSendOnly(context: android.content.Context, dryRun: Boolean, onLine: (String) -> Unit): String {
    val log = StringBuilder()
    fun append(line: String) {
        log.append(line).append("\n")
        onLine(line)
    }
    try {
        val geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
        val sheetsAuth = SheetsAuth(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY)
        val sheetsService = SheetsService(sheetsAuth, BuildConfig.SHEETS_SPREADSHEET_ID)
        val resumeBytes = ResumeStore.loadPrimaryResumeBytes(context)
        val resumeText = ResumeStore.extractResumeText(resumeBytes)
        val candidate = Settings.getCandidateConfig(context)
        runSendPipeline(
            geminiClient = geminiClient,
            sheetsService = sheetsService,
            context = context,
            masterResumeText = resumeText,
            resumeBytes = resumeBytes,
            candidate = candidate,
            maxJobsPerRun = Settings.getMaxJobsPerRun(context),
            dryRun = dryRun,
            onProgress = { append(it) },
        )
    } catch (e: Exception) {
        append("FAILED: ${e.message}")
    }
    return log.toString()
}

@Composable
private fun SmtpTestCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    ToolCard(
        title = "SMTP test",
        description = "Sends one real email to your own Gmail address, isolated from the Sheets/Tracker pipeline — proves Gmail sending works without touching a real recruiter.",
    ) {
        Button(
            onClick = {
                running = true
                log = ""
                scope.launch {
                    log = runSmtpTest(context)
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Sending..." else "Send test email to self")
        }
        if (log.isNotEmpty()) LogText(log)
    }
}

private suspend fun runSmtpTest(context: android.content.Context): String {
    return try {
        val resumeBytes = ResumeStore.loadPrimaryResumeBytes(context)
        val candidateName = Settings.getCandidateProfile(context).name
        sendMail(
            gmailUser = BuildConfig.GMAIL_USER,
            gmailAppPassword = BuildConfig.GMAIL_APP_PASSWORD,
            candidateName = candidateName,
            email = OutgoingEmail(
                to = BuildConfig.GMAIL_USER,
                subject = "Nahi App SMTP test — ${Instant.now()}",
                body = "This is a real test send from the Android app's SmtpMailer, isolated from the Sheets/Tracker pipeline. If you received this with the resume attached, the SMTP path works.",
                attachmentBytes = resumeBytes,
                attachmentFilename = "${candidateName.replace(" ", "_")}_Resume.pdf",
            ),
            dryRun = false,
        )
        "Sent successfully. Check ${BuildConfig.GMAIL_USER}'s inbox."
    } catch (e: Exception) {
        "FAILED: ${e.message}"
    }
}

@Composable
private fun LogText(log: String) {
    Text(
        log,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp),
    )
}
