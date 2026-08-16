package com.rohitgande.sdetoutreach.scraper

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Invisible, window-attached WebView for unattended background scraping. WebView needs a
// real attached window for layout/measure and scroll-triggered lazy-load to behave
// reliably — an unattached WebView with manual measure()/layout() calls is unreliable
// across WebView versions (validated against current Android platform guidance before
// building this — see the plan). Requires the user to have granted "Display over other
// apps" once via Settings; the overlay itself is a 1x1, untouchable, unfocusable window
// the user never sees.
class OverlayWebViewHost(private val context: Context) {

    private var webView: WebView? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun isOverlayPermissionGranted(): Boolean = Settings.canDrawOverlays(context)

    suspend fun create(): WebView = withContext(Dispatchers.Main) {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        windowManager.addView(wv, params)
        webView = wv
        wv
    }

    suspend fun destroy() = withContext(Dispatchers.Main) {
        webView?.let {
            windowManager.removeView(it)
            it.destroy()
        }
        webView = null
    }
}
