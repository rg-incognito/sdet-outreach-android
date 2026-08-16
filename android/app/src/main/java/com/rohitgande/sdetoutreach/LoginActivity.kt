@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme

// LinkedIn login inside an in-app WebView. WebView's CookieManager persists cookies to
// disk automatically (durable across app restarts) — there is no separate export step
// like the Node tool's data/storageState.json; the scraper reuses this same WebView's
// cookie jar directly.
class LoginActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdetOutreachTheme {
                LoginScreen(
                    onBack = { finish() },
                    onDone = {
                        CookieManager.getInstance().flush()
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("https://www.linkedin.com/login")
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log into LinkedIn") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Once you're logged in on the page above, tap Done — your session is saved automatically.")
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text("  Done — I'm logged in")
                }
            }
        }
    }
}
