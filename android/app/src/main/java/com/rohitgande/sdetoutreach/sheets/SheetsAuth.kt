package com.rohitgande.sdetoutreach.sheets

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

// Hand-rolled service-account JWT auth (RS256), replacing google-auth-library's JWT
// client (src/sheets/client.ts). Standard JDK crypto is sufficient — Google service
// account keys ship as PKCS8 PEM, exactly what PKCS8EncodedKeySpec expects; no
// BouncyCastle needed. BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY already contains the real
// multi-line PEM (compiler restores the escaped newlines at compile time — see
// build.gradle.kts's secretLiteral), so no \n-unescaping is needed here, unlike
// src/config.ts's .env-driven equivalent.
class SheetsAuth(private val serviceAccountEmail: String, privateKeyPem: String) {

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val privateKey: PrivateKey = run {
        val cleaned = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    // In-memory cache, refreshed ~60s before actual expiry — avoids re-minting a JWT
    // assertion (and a token-endpoint round trip) on every Sheets call within a run.
    private var cachedToken: String? = null
    private var cachedTokenExpiryMs: Long = 0

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun mintJwt(): String {
        val nowSec = System.currentTimeMillis() / 1000
        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }
        val claims = JSONObject().apply {
            put("iss", serviceAccountEmail)
            put("scope", SCOPE)
            put("aud", TOKEN_URL)
            put("iat", nowSec)
            put("exp", nowSec + 3600)
        }
        val signingInput = base64Url(header.toString().toByteArray(Charsets.UTF_8)) + "." +
            base64Url(claims.toString().toByteArray(Charsets.UTF_8))

        val signatureBytes = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
        }.sign()

        return "$signingInput.${base64Url(signatureBytes)}"
    }

    suspend fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedTokenExpiryMs) return it }

        val jwt = mintJwt()
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        return withContext(Dispatchers.IO) { http.newCall(request).execute() }.use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                // A device with a badly skewed clock surfaces here as invalid_grant.
                throw RuntimeException("Token exchange failed: HTTP ${response.code}: ${responseBody.take(300)}")
            }
            val json = JSONObject(responseBody)
            val token = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)
            cachedToken = token
            cachedTokenExpiryMs = now + (expiresIn - 60) * 1000
            token
        }
    }
}
