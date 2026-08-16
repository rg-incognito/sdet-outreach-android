package com.rohitgande.sdetoutreach

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// LinkedIn login inside an in-app WebView. WebView's CookieManager persists cookies to
// disk automatically (durable across app restarts) — there is no separate export step
// like the Node tool's data/storageState.json; the scraper reuses this same WebView's
// cookie jar directly.
//
// Deliberately NOT built with Compose's AndroidView: on this device (Vivo/OriginOS,
// Android 13, WebView 150.x) a WebView wrapped in Compose's AndroidView renders
// completely blank — the page loads and its DOM populates normally (confirmed via
// chrome://inspect: readyState complete, full form text present) but zero pixels are
// ever composited to the screen, even after trying software layer type, disabling
// hardware acceleration, and moving loadUrl() into AndroidView's update callback. A
// plain, non-Compose Activity with the exact same WebView setup renders correctly
// (verified with DebugWebViewActivity), so this screen is built with plain Android
// views instead.
class LoginActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val webView = WebView(this).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    view.loadUrl("https://www.linkedin.com/login")
                    return true
                }
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(
                TextView(this@LoginActivity).apply {
                    text = "← Back"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, 0, dp(24), 0)
                    setOnClickListener { finish() }
                },
            )
            addView(
                TextView(this@LoginActivity).apply {
                    text = "Log into LinkedIn"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                },
            )
        }

        val instructions = TextView(this).apply {
            text = "Once you're logged in on the page above, tap Done — your session is saved automatically."
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val doneButton = Button(this).apply {
            text = "Done — I'm logged in"
            setOnClickListener {
                CookieManager.getInstance().flush()
                setResult(RESULT_OK)
                finish()
            }
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(doneButton)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                header,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                instructions,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                footer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        setContentView(root)
        webView.loadUrl("https://www.linkedin.com/login")
    }
}
