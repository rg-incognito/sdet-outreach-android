package com.rohitgande.sdetoutreach

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

// Deliberately minimal: no Compose, no theme, no AndroidView wrapping, no cookie/JS
// settings beyond the bare minimum to load a page. Used to isolate whether a blank
// LinkedIn login page is caused by something in the app's Compose/AndroidView setup
// versus a plain android.webkit.WebView on this device. Every lifecycle event and every
// sub-resource response is logged to logcat under tag "DebugWV" so the outcome can be
// read with `adb logcat -s DebugWV` without needing a screenshot.
private const val TAG = "DebugWV"

class DebugWebViewActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "onPageStarted url=$url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                Log.d(TAG, "onPageFinished url=$url")
                view.evaluateJavascript(
                    "document.body ? document.body.innerText.length : -1",
                ) { result -> Log.d(TAG, "body innerText length=$result") }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                Log.e(
                    TAG,
                    "onReceivedError url=${request.url} isMainFrame=${request.isForMainFrame} " +
                        "errorCode=${error.errorCode} description=${error.description}",
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                Log.e(
                    TAG,
                    "onReceivedHttpError url=${request.url} isMainFrame=${request.isForMainFrame} " +
                        "status=${errorResponse.statusCode} reason=${errorResponse.reasonPhrase}",
                )
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                Log.e(TAG, "onRenderProcessGone didCrash=${detail.didCrash()} reason=${detail.rendererPriorityAtExit()}")
                return true
            }
        }

        setContentView(webView)
        Log.d(TAG, "loadUrl -> https://www.linkedin.com/login")
        webView.loadUrl("https://www.linkedin.com/login")
    }
}
