@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme
import com.rohitgande.sdetoutreach.worker.isPeriodicRunScheduled

private fun isLoggedIntoLinkedIn(): Boolean {
    // "li_at" is LinkedIn's own session-auth cookie name — its presence is a reasonable
    // proxy for "logged in" without needing to load any LinkedIn page just to check.
    val cookies = CookieManager.getInstance().getCookie("https://www.linkedin.com") ?: return false
    return cookies.split(";").any { it.trim().startsWith("li_at=") }
}

class MainActivity : ComponentActivity() {

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdetOutreachTheme {
                MainScreen(
                    onLogin = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) },
                    onRunNow = { dryRun ->
                        startActivity(Intent(this, RunActivity::class.java).putExtra(RunActivity.EXTRA_DRY_RUN, dryRun))
                    },
                    onOpenAdvanced = { startActivity(Intent(this, AdvancedActivity::class.java)) },
                    onOpenBackground = { startActivity(Intent(this, BackgroundActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    onLogin: () -> Unit,
    onRunNow: (dryRun: Boolean) -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(isLoggedIntoLinkedIn()) }
    var scheduled by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                loggedIn = isLoggedIntoLinkedIn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(loggedIn) {
        scheduled = isPeriodicRunScheduled(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nahi App") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusRow(
                label = if (loggedIn) "LinkedIn session active" else "Not logged in yet",
                ok = loggedIn,
            )
            StatusRow(
                label = if (scheduled) "Background runs scheduled (~every 2h)" else "Background runs not scheduled",
                ok = scheduled,
                icon = Icons.Filled.Schedule,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Run Now",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Scrapes LinkedIn for new posts, then emails outreach for everyone pending. Equivalent to running the pipeline end to end.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = dryRun, onCheckedChange = { dryRun = it })
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (dryRun) "Dry run (no real emails sent)" else "Live — will send real emails",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Button(
                        onClick = { onRunNow(dryRun) },
                        enabled = loggedIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Run Now")
                    }
                    if (!loggedIn) {
                        Text(
                            "Log into LinkedIn first to enable this.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                Text(if (loggedIn) "Re-login to LinkedIn" else "Log into LinkedIn")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenBackground, modifier = Modifier.fillMaxWidth()) {
                Text("Background & Scheduling")
            }
            OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
                Text("Advanced (debug tools)")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon ?: if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
