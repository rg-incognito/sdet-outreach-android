@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rohitgande.sdetoutreach.data.Settings as AppSettings
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme
import com.rohitgande.sdetoutreach.worker.cancelPeriodicRun
import com.rohitgande.sdetoutreach.worker.isPeriodicRunScheduled
import com.rohitgande.sdetoutreach.worker.schedulePeriodicRun

// The one-time manual setup this needs, mirroring the Termux setup's own battery-
// exemption + wake-lock steps (TERMUX_SETUP.md) — "Display over other apps" lets the
// background worker attach an invisible WebView to a real window (required for reliable
// scroll/lazy-load), and the battery exemption keeps the ~2h periodic job from being
// killed by OEM battery managers.
class BackgroundActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdetOutreachTheme {
                BackgroundScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun BackgroundScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
            } else true,
        )
    }
    var scheduled by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    fun refresh() {
        overlayGranted = Settings.canDrawOverlays(context)
        batteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(overlayGranted, batteryExempt) {
        scheduled = isPeriodicRunScheduled(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Background & Scheduling") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PermissionCard(
                granted = overlayGranted,
                title = "Display over other apps",
                description = "Needed so a background run can attach an invisible browser window while the app isn't open.",
                buttonLabel = "Open settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
            PermissionCard(
                granted = batteryExempt,
                title = "Battery optimization exempt",
                description = "Stops the OS from killing the ~2h periodic job to save battery.",
                buttonLabel = "Open settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (scheduled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (scheduled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (scheduled) "Background runs scheduled (~every 2h)" else "Background runs not scheduled",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        "Only one automation should run against this LinkedIn account/Sheet at a time. Don't enable this while the phone's Termux cron job is also active.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (scheduled) {
                        OutlinedButton(
                            onClick = {
                                cancelPeriodicRun(context)
                                AppSettings.setBackgroundEnabled(context, false)
                                scheduled = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disable background runs") }
                    } else {
                        Button(
                            onClick = {
                                schedulePeriodicRun(context)
                                AppSettings.setBackgroundEnabled(context, true)
                                scheduled = true
                            },
                            enabled = overlayGranted,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Enable background runs") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    granted: Boolean,
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
