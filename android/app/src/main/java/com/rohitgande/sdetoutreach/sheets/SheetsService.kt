package com.rohitgande.sdetoutreach.sheets

import com.rohitgande.sdetoutreach.util.encodeURIComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Generic row-as-map REST wrapper around Sheets API v4, replacing google-spreadsheet's
// GoogleSpreadsheet/GoogleSpreadsheetRow (src/sheets/client.ts). PostsSheet.kt and
// TrackerSheet.kt are built directly on top of this, mirroring the Node originals'
// sheet.getRows()/row.get(col)/row.assign()+save() shape closely enough to port their
// logic almost 1:1.
class SheetsService(private val auth: SheetsAuth, private val spreadsheetId: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val knownSheets = mutableSetOf<String>()

    private fun columnLetter(oneBasedIndex: Int): String {
        var n = oneBasedIndex
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private suspend fun authorizedRequest(builder: Request.Builder): Request {
        val token = auth.getAccessToken()
        return builder.addHeader("Authorization", "Bearer $token").build()
    }

    private suspend fun execute(request: Request): JSONObject {
        return withContext(Dispatchers.IO) { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Sheets API HTTP ${response.code}: ${body.take(500)}")
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    // Port of getOrCreateSheet (client.ts:30-37): creates the tab with a header row if it
    // doesn't already exist; leaves an existing sheet's header row untouched.
    suspend fun getOrCreateSheet(title: String, headers: List<String>) {
        if (title in knownSheets) return

        val metaRequest = authorizedRequest(
            Request.Builder().url(
                "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId?fields=sheets.properties.title"
            ).get()
        )
        val meta = execute(metaRequest)
        val sheetsArray = meta.optJSONArray("sheets") ?: JSONArray()
        val existingTitles = (0 until sheetsArray.length()).map {
            sheetsArray.getJSONObject(it).getJSONObject("properties").getString("title")
        }.toSet()

        if (title !in existingTitles) {
            val batchBody = JSONObject().apply {
                put("requests", JSONArray().apply {
                    put(JSONObject().apply {
                        put("addSheet", JSONObject().apply {
                            put("properties", JSONObject().apply { put("title", title) })
                        })
                    })
                })
            }.toString().toRequestBody("application/json".toMediaType())
            execute(
                authorizedRequest(
                    Request.Builder().url(
                        "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId:batchUpdate"
                    ).post(batchBody)
                )
            )
            writeRow(title, 1, headers)
        }
        knownSheets.add(title)
    }

    private suspend fun writeRow(title: String, rowNumber: Int, values: List<String>) {
        val lastCol = columnLetter(values.size)
        val range = encodeURIComponent("$title!A$rowNumber:$lastCol$rowNumber")
        val body = JSONObject().apply {
            put("values", JSONArray().apply { put(JSONArray(values)) })
        }.toString().toRequestBody("application/json".toMediaType())
        execute(
            authorizedRequest(
                Request.Builder().url(
                    "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range?valueInputOption=RAW"
                ).put(body)
            )
        )
    }

    // Returns every data row (excluding the header) as header-keyed string maps, plus
    // each row's 1-based sheet row number (needed by upsert-by-match callers like
    // TrackerSheet to build an exact update range).
    suspend fun getRows(title: String, headers: List<String>): List<Pair<Int, Map<String, String>>> {
        val lastCol = columnLetter(headers.size)
        val range = encodeURIComponent("$title!A2:$lastCol")
        val request = authorizedRequest(
            Request.Builder().url(
                "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range"
            ).get()
        )
        val response = execute(request)
        val values = response.optJSONArray("values") ?: JSONArray()

        return (0 until values.length()).map { i ->
            val row = values.getJSONArray(i)
            val map = headers.mapIndexed { colIndex, header ->
                header to if (colIndex < row.length()) row.optString(colIndex, "") else ""
            }.toMap()
            (i + 2) to map // +2: row 1 is the header, data starts at row 2
        }
    }

    suspend fun appendRows(title: String, headers: List<String>, rows: List<Map<String, Any>>) {
        if (rows.isEmpty()) return
        val values = JSONArray().apply {
            rows.forEach { row ->
                put(JSONArray().apply { headers.forEach { h -> put(row[h] ?: "") } })
            }
        }
        val body = JSONObject().apply { put("values", values) }
            .toString().toRequestBody("application/json".toMediaType())
        val range = encodeURIComponent("$title!A1")
        execute(
            authorizedRequest(
                Request.Builder().url(
                    "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range:append" +
                        "?valueInputOption=RAW&insertDataOption=INSERT_ROWS"
                ).post(body)
            )
        )
    }

    suspend fun updateRow(title: String, rowNumber: Int, headers: List<String>, row: Map<String, Any>) {
        val values = headers.map { h -> row[h] ?: "" }
        val lastCol = columnLetter(headers.size)
        val range = encodeURIComponent("$title!A$rowNumber:$lastCol$rowNumber")
        val body = JSONObject().apply {
            put("values", JSONArray().apply { put(JSONArray(values)) })
        }.toString().toRequestBody("application/json".toMediaType())
        execute(
            authorizedRequest(
                Request.Builder().url(
                    "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range?valueInputOption=RAW"
                ).put(body)
            )
        )
    }
}
