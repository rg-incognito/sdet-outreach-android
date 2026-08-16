package com.rohitgande.sdetoutreach.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Port of src/gemini/client.ts: REST equivalent of the @google/genai SDK. On any failure
// (there is no special-casing of 429/RESOURCE_EXHAUSTED — every exception is treated the
// same) walks a whole model chain: env-style primary/fallback first, then every other
// model this API key can call generateContent on, ranked flash-lite -> flash -> pro ->
// everything else. Only throws once every model in the chain has failed.
class GeminiClient(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiClient"
        private const val PRIMARY_MODEL = "gemini-flash-lite-latest"
        private const val FALLBACK_MODEL = "gemini-flash-latest"
        private val PREFERENCE_ORDER = listOf("flash-lite", "flash", "pro")
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cachedModelChain: List<String>? = null

    private fun preferenceRank(name: String): Int {
        val idx = PREFERENCE_ORDER.indexOfFirst { name.contains(it) }
        return if (idx == -1) PREFERENCE_ORDER.size else idx
    }

    private suspend fun discoverModelChain(): List<String> {
        cachedModelChain?.let { return it }

        val discovered = mutableListOf<String>()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/models?key=$apiKey")
                .get()
                .build()
            withContext(Dispatchers.IO) { http.newCall(request).execute() }.use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val models = json.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until models.length()) {
                        val model = models.getJSONObject(i)
                        val name = model.optString("name", "")
                        val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        var supportsGenerate = false
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") supportsGenerate = true
                        }
                        if (name.isNotEmpty() && supportsGenerate) {
                            discovered.add(name.removePrefix("models/"))
                        }
                    }
                } else {
                    Log.w(TAG, "models.list failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "models.list failed: ${e.message}")
        }

        val rest = discovered
            .filter { it != PRIMARY_MODEL && it != FALLBACK_MODEL }
            .sortedBy { preferenceRank(it) }

        val chain = listOf(PRIMARY_MODEL, FALLBACK_MODEL) + rest
        cachedModelChain = chain
        return chain
    }

    private suspend fun generateWithModel(model: String, prompt: String): String {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini ($model) HTTP ${response.code}: ${body.take(300)}")
            }
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            val text = candidates
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")

            if (text.isNullOrEmpty()) {
                throw RuntimeException("Gemini ($model) returned an empty response")
            }
            text
        }
    }

    suspend fun generateText(prompt: String): String {
        val chain = discoverModelChain()
        var lastError: Exception? = null

        for ((index, model) in chain.withIndex()) {
            try {
                return generateWithModel(model, prompt)
            } catch (e: Exception) {
                lastError = e
                if (index < chain.size - 1) {
                    Log.w(TAG, "Model $model failed (${e.message}), trying ${chain[index + 1]}")
                } else {
                    Log.e(TAG, "Model $model failed (${e.message}); no more models in chain")
                }
            }
        }
        throw lastError ?: RuntimeException("No callable Gemini models found")
    }
}
