package com.rohitgande.sdetoutreach.scraper

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rohitgande.sdetoutreach.model.JobPosting
import com.rohitgande.sdetoutreach.util.encodeURIComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONTokener
import java.security.MessageDigest
import kotlin.random.Random

// Port of src/scrapers/linkedin.ts. LinkedIn's content-search feed (not Jobs search) is
// scraped because casual feed/content posts often contain a real recruiter email
// (LinkedIn auto-linkifies "send resume to x@y.com" as a mailto: anchor), unlike formal
// Jobs listings which funnel applicants through LinkedIn's own apply flow.
object LinkedInScraper {

    private const val TAG = "LinkedInScraper"
    private const val MAX_LOAD_MORE_ATTEMPTS = 40
    private const val POST_ITEM_SELECTOR = "div[role=\"listitem\"]"

    data class ScrapeConfig(
        val queries: List<String>,
        val maxJobsPerRun: Int,
        val regionGeoUrn: String,
    )

    private data class ExtractedPost(
        val authorName: String,
        val authorUrl: String?,
        val postText: String,
        val emails: List<String>,
    )

    suspend fun scrape(webView: WebView, config: LinkedInScraper.ScrapeConfig): List<JobPosting> {
        val results = mutableListOf<JobPosting>()
        val seenHashes = mutableSetOf<String>()

        for (query in config.queries) {
            if (results.size >= config.maxJobsPerRun) break

            var searchUrl = "https://www.linkedin.com/search/results/content/" +
                "?keywords=${encodeURIComponent(query)}" +
                "&origin=FACETED_SEARCH" +
                "&sortBy=${encodeURIComponent("[\"date_posted\"]")}"
            if (config.regionGeoUrn.isNotEmpty()) {
                searchUrl += "&geoUrn=${encodeURIComponent("[\"${config.regionGeoUrn}\"]")}"
            }

            try {
                navigateAndWait(webView, searchUrl)
                randomDelay(2000, 5000)

                val targetCount = config.maxJobsPerRun - results.size
                val rawCount = loadMorePosts(webView, targetCount)
                val count = minOf(rawCount, targetCount)

                if (count == 0) {
                    val bodyText = evalJsRaw(webView, "document.body ? document.body.innerText : ''")
                    val reason = if (bodyText.contains("No results found")) {
                        "LinkedIn returned zero real results for this query"
                    } else {
                        "selectors may be stale, or the request was blocked"
                    }
                    Log.w(TAG, "No posts found for query \"$query\" — $reason.")
                    continue
                }

                val extracted = extractPosts(webView, count)
                for (post in extracted) {
                    if (post.postText.isBlank()) continue

                    val hash = shortHash(post.postText)
                    if (!seenHashes.add(hash)) continue

                    val descriptionText = if (post.emails.isNotEmpty()) {
                        "${post.postText}\n\nContact: ${post.emails.joinToString(", ")}"
                    } else {
                        post.postText
                    }

                    val url = "${post.authorUrl ?: searchUrl}#post-$hash"
                    results.add(
                        JobPosting(
                            id = url,
                            url = url,
                            title = descriptionText,
                            company = post.authorName,
                            descriptionText = descriptionText,
                            scrapedAt = java.time.Instant.now().toString(),
                        )
                    )
                    if (results.size >= config.maxJobsPerRun) break
                    randomDelay(2000, 5000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Query \"$query\" failed: ${e.message}", e)
            }
        }

        return results
    }

    private suspend fun loadMorePosts(webView: WebView, targetCount: Int): Int {
        var previousCount = -1
        for (attempt in 0 until MAX_LOAD_MORE_ATTEMPTS) {
            val currentCount = countItems(webView)
            if (currentCount >= targetCount || currentCount == previousCount) break
            previousCount = currentCount
            scrollLastIntoView(webView)
            randomDelay(1200, 2200)
        }
        return countItems(webView)
    }

    private suspend fun countItems(webView: WebView): Int {
        val js = "document.querySelectorAll('$POST_ITEM_SELECTOR').length"
        return evalJsRaw(webView, js).toIntOrNull() ?: 0
    }

    private suspend fun scrollLastIntoView(webView: WebView) {
        val js = """
            (function(){
              var items = document.querySelectorAll('$POST_ITEM_SELECTOR');
              if (items.length > 0) { items[items.length-1].scrollIntoView(); }
            })();
        """.trimIndent()
        evalJsRaw(webView, js)
    }

    private suspend fun extractPosts(webView: WebView, count: Int): List<ExtractedPost> {
        val js = """
            (function(){
              var items = document.querySelectorAll('$POST_ITEM_SELECTOR');
              var results = [];
              var n = Math.min(items.length, $count);
              for (var i = 0; i < n; i++) {
                var item = items[i];
                var expandBtn = item.querySelector('[data-testid="expandable-text-button"]');
                if (expandBtn) { try { expandBtn.click(); } catch(e) {} }
                var authorLink = item.querySelector('a');
                var authorUrl = authorLink ? authorLink.getAttribute('href') : null;
                var authorRaw = authorLink ? (authorLink.textContent || '') : '';
                var authorName = authorRaw.split(String.fromCharCode(8226))[0].trim() || 'Unknown';
                var postText = (item.textContent || '').trim();
                var emailSet = {};
                var emailEls = item.querySelectorAll('a[href^="mailto:" i]');
                for (var j = 0; j < emailEls.length; j++) {
                  var href = emailEls[j].getAttribute('href') || '';
                  var email = href.replace(/^mailto:/i, '').split('?')[0].trim();
                  if (email) { emailSet[email] = true; }
                }
                results.push({
                  authorName: authorName,
                  authorUrl: authorUrl,
                  postText: postText,
                  emails: Object.keys(emailSet)
                });
              }
              return JSON.stringify(results);
            })();
        """.trimIndent()

        val raw = evalJsRaw(webView, js)
        val jsonString = try {
            JSONTokener(raw).nextValue() as String
        } catch (e: Exception) {
            "[]"
        }
        val array = JSONArray(jsonString)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val emails = obj.getJSONArray("emails")
            ExtractedPost(
                authorName = obj.optString("authorName", "Unknown"),
                authorUrl = if (obj.isNull("authorUrl")) null else obj.optString("authorUrl"),
                postText = obj.optString("postText", ""),
                emails = (0 until emails.length()).map { emails.getString(it) },
            )
        }
    }

    private fun shortHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private suspend fun randomDelay(minMs: Int, maxMs: Int) {
        delay((minMs + Random.nextDouble() * (maxMs - minMs)).toLong())
    }

    private suspend fun navigateAndWait(webView: WebView, url: String) = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (!deferred.isCompleted) deferred.complete(Unit)
            }
        }
        webView.loadUrl(url)
        withTimeoutOrNull(30000) { deferred.await() }
    }

    private suspend fun evalJsRaw(webView: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(result ?: "null", null)
            }
        }
    }
}
