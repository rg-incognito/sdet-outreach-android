package com.rohitgande.sdetoutreach.pipeline

import android.content.Context
import android.util.Log
import com.rohitgande.sdetoutreach.audit.AuditEntry
import com.rohitgande.sdetoutreach.audit.recordSentEmail
import com.rohitgande.sdetoutreach.gemini.GeminiClient
import com.rohitgande.sdetoutreach.gemini.extractSkills
import com.rohitgande.sdetoutreach.gemini.generateOutreach
import com.rohitgande.sdetoutreach.mail.OutgoingEmail
import com.rohitgande.sdetoutreach.mail.sendMail
import com.rohitgande.sdetoutreach.model.JobPosting
import com.rohitgande.sdetoutreach.resume.scoreResumeAgainstKeywords
import com.rohitgande.sdetoutreach.sheets.SendStatus
import com.rohitgande.sdetoutreach.sheets.SheetsService
import com.rohitgande.sdetoutreach.sheets.TrackerRow
import com.rohitgande.sdetoutreach.sheets.upsertTrackerRow
import com.rohitgande.sdetoutreach.util.displayTitle
import java.time.Instant

private const val TAG = "EmailJob"

data class EmailJobResult(val sent: Boolean, val score: Int)

data class CandidateConfig(
    val name: String,
    val phone: String,
    val currentCTC: String,
    val expectedCTC: String,
    val noticePeriod: String,
    val location: String,
    val gmailUser: String,
    val gmailAppPassword: String,
)

private fun sanitizeFilename(input: String): String =
    input.replace(Regex("[^a-z0-9]+", RegexOption.IGNORE_CASE), "-").trim('-').take(80)

// Direct port of src/emailJob.ts's tailorAndSend. V1 scope: resume attachment is always
// the raw bytes loaded once by the caller (PDF passthrough only — see ResumeStore),
// never AI-rewritten, matching the Node original's "never rewrite the resume, only the
// outreach email" guarantee. Never throws for a mid-flight failure — reports it via the
// return value and a Tracker "Failed" row instead, so a failed attempt is automatically
// retried on the next run (trackerSheet.ts's getSentEmails only treats Status "Sent" as
// blocking).
suspend fun tailorAndSend(
    geminiClient: GeminiClient,
    sheetsService: SheetsService,
    context: Context,
    masterResumeText: String,
    resumeBytes: ByteArray,
    candidate: CandidateConfig,
    job: JobPosting,
    recruiterEmail: String,
    dryRun: Boolean,
): EmailJobResult {
    val roleTitle = displayTitle(job.title)
    Log.i(TAG, "Processing: $roleTitle @ ${job.company} ($recruiterEmail)")

    var score = 0
    var subject = ""
    var body = ""

    try {
        val jdKeywords = extractSkills(geminiClient, job.descriptionText)
        val scored = scoreResumeAgainstKeywords(masterResumeText, jdKeywords)
        score = scored.score

        val outreach = generateOutreach(
            client = geminiClient,
            job = job,
            matchedKeywords = scored.matchedKeywords,
            candidateName = candidate.name,
            candidatePhone = candidate.phone,
            roleTitle = roleTitle,
            masterResume = masterResumeText,
            candidateCurrentCTC = candidate.currentCTC,
            candidateExpectedCTC = candidate.expectedCTC,
            candidateNoticePeriod = candidate.noticePeriod,
            candidateLocation = candidate.location,
        )
        subject = outreach.subject
        body = outreach.body
        val attachmentFilename = "${sanitizeFilename(candidate.name)}_Resume.pdf"

        sendMail(
            gmailUser = candidate.gmailUser,
            gmailAppPassword = candidate.gmailAppPassword,
            candidateName = candidate.name,
            email = OutgoingEmail(recruiterEmail, subject, body, resumeBytes, attachmentFilename),
            dryRun = dryRun,
        )

        if (dryRun) {
            Log.i(TAG, "[dry-run] ATS score: $score — matched keywords: ${scored.matchedKeywords.joinToString(", ").ifEmpty { "(none)" }}")
            return EmailJobResult(sent = false, score = score)
        }

        val sentAt = Instant.now().toString()
        recordSentEmail(
            context,
            AuditEntry(
                recipient = recruiterEmail,
                jobTitle = roleTitle,
                company = job.company,
                jobUrl = job.url,
                atsScore = score,
                sentAt = sentAt,
            ),
        )
        upsertTrackerRow(
            sheetsService,
            TrackerRow(
                updatedAt = sentAt,
                status = SendStatus.Sent,
                jobTitle = roleTitle,
                company = job.company,
                recruiterEmail = recruiterEmail,
                atsScore = score,
                subject = subject,
                body = body,
                error = "",
                jobUrl = job.url,
                description = job.descriptionText,
            ),
        )
        return EmailJobResult(sent = true, score = score)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to send to $recruiterEmail for $roleTitle @ ${job.company}: ${e.message}")

        if (!dryRun) {
            upsertTrackerRow(
                sheetsService,
                TrackerRow(
                    updatedAt = Instant.now().toString(),
                    status = SendStatus.Failed,
                    jobTitle = roleTitle,
                    company = job.company,
                    recruiterEmail = recruiterEmail,
                    atsScore = score,
                    subject = subject,
                    body = body,
                    error = e.message ?: "",
                    jobUrl = job.url,
                    description = job.descriptionText,
                ),
            )
        }
        return EmailJobResult(sent = false, score = score)
    }
}
