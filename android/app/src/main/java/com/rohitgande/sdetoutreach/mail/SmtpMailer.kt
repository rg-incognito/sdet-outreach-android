package com.rohitgande.sdetoutreach.mail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

private const val TAG = "SmtpMailer"

data class OutgoingEmail(
    val to: String,
    val subject: String,
    val body: String,
    val attachmentBytes: ByteArray,
    val attachmentFilename: String,
)

private class ByteArrayDataSource(private val bytes: ByteArray, private val type: String) : DataSource {
    override fun getInputStream(): java.io.InputStream = bytes.inputStream()
    override fun getOutputStream(): java.io.OutputStream = throw UnsupportedOperationException()
    override fun getContentType(): String = type
    override fun getName(): String = "attachment"
}

// Direct port of src/mailer/sendMail.ts. Gmail SMTP (smtp.gmail.com:465, SSL) with an App
// Password — the same model the Node tool already uses, ported as-is rather than moving
// to Gmail API + OAuth2 (see plan: App Passwords remain supported, and this is meaningfully
// less implementation/maintenance for a single-account personal tool).
suspend fun sendMail(gmailUser: String, gmailAppPassword: String, candidateName: String, email: OutgoingEmail, dryRun: Boolean) {
    if (dryRun) {
        Log.i(TAG, "[dry-run] Would send email to ${email.to} — subject: \"${email.subject}\"")
        return
    }

    if (gmailAppPassword.isEmpty()) {
        throw RuntimeException("Gmail App Password is not set — cannot send real email. Set it or use dry-run.")
    }

    withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.socketFactory.port", "465")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        }
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(gmailUser, gmailAppPassword)
        })

        val message = MimeMessage(session)
        message.setFrom(
            if (candidateName.isNotEmpty()) InternetAddress(gmailUser, candidateName) else InternetAddress(gmailUser)
        )
        message.setRecipient(Message.RecipientType.TO, InternetAddress(email.to))
        message.subject = email.subject

        val bodyPart = MimeBodyPart().apply { setText(email.body) }
        val attachmentPart = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(email.attachmentBytes, "application/pdf"))
            fileName = email.attachmentFilename
        }
        val multipart = MimeMultipart().apply {
            addBodyPart(bodyPart)
            addBodyPart(attachmentPart)
        }
        message.setContent(multipart)

        Transport.send(message)
    }

    Log.i(TAG, "Sent email to ${email.to} — subject: \"${email.subject}\"")
}
