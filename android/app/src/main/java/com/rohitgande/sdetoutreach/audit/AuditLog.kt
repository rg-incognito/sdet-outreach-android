package com.rohitgande.sdetoutreach.audit

import android.content.Context
import org.json.JSONObject
import java.io.File

// Direct port of src/store/auditLog.ts. Written to app-internal storage
// (filesDir/logs/audit.log) rather than a path relative to process cwd — same JSONL
// format and field order.
data class AuditEntry(
    val recipient: String,
    val jobTitle: String,
    val company: String,
    val jobUrl: String,
    val atsScore: Int,
    val sentAt: String,
)

fun recordSentEmail(context: Context, entry: AuditEntry) {
    val logDir = File(context.filesDir, "logs")
    logDir.mkdirs()
    val logFile = File(logDir, "audit.log")

    val json = JSONObject().apply {
        put("recipient", entry.recipient)
        put("jobTitle", entry.jobTitle)
        put("company", entry.company)
        put("jobUrl", entry.jobUrl)
        put("atsScore", entry.atsScore)
        put("sentAt", entry.sentAt)
    }
    logFile.appendText(json.toString() + "\n")
}
