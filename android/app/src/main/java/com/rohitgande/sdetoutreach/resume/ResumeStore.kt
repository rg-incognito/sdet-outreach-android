package com.rohitgande.sdetoutreach.resume

import android.content.Context
import android.net.Uri
import com.rohitgande.sdetoutreach.data.Settings
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

data class StoredResume(val filename: String, val sizeBytes: Long, val isPrimary: Boolean, val isBundled: Boolean = false)

// Resumes the user has imported live in filesDir/resumes/ as plain PDF files, named after
// their original filename. One is marked "primary" (Settings.primaryResumeFilename, null
// meaning "the bundled default") and used by the pipeline. The PDF bundled in APK assets
// (interim test scaffolding — see CLAUDE.md) is always listed too, alongside imports, so
// it stays a selectable option rather than disappearing once something else is imported.
object ResumeStore {

    private fun resumesDir(context: Context): File =
        File(context.filesDir, "resumes").apply { mkdirs() }

    private fun loadBundledResumeBytes(context: Context): ByteArray =
        context.assets.open("resume.pdf").use { it.readBytes() }

    fun listResumes(context: Context): List<StoredResume> {
        val primary = Settings.getPrimaryResumeFilename(context)
        val bundled = StoredResume(
            filename = "resume.pdf",
            sizeBytes = loadBundledResumeBytes(context).size.toLong(),
            isPrimary = primary == null,
            isBundled = true,
        )
        val files = resumesDir(context).listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
            ?: emptyArray()
        val imported = files.sortedBy { it.name.lowercase() }.map { f ->
            StoredResume(filename = f.name, sizeBytes = f.length(), isPrimary = f.name == primary)
        }
        return listOf(bundled) + imported
    }

    // Copies the picked file's bytes into app-private storage under its display name
    // (de-duplicated if that name is already taken). If this is the first resume ever
    // imported, it's automatically made primary.
    fun importResume(context: Context, uri: Uri, displayName: String): StoredResume {
        val safeName = displayName.ifBlank { "resume.pdf" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        var target = File(resumesDir(context), safeName)
        if (target.exists()) {
            val base = safeName.removeSuffix(".pdf")
            var n = 2
            while (target.exists()) {
                target = File(resumesDir(context), "$base ($n).pdf")
                n++
            }
        }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the picked file" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (Settings.getPrimaryResumeFilename(context) == null) {
            Settings.setPrimaryResumeFilename(context, target.name)
        }
        return StoredResume(target.name, target.length(), isPrimary = Settings.getPrimaryResumeFilename(context) == target.name)
    }

    // filename null means "make the bundled default primary".
    fun setPrimary(context: Context, filename: String?) {
        Settings.setPrimaryResumeFilename(context, filename)
    }

    fun delete(context: Context, filename: String) {
        File(resumesDir(context), filename).delete()
        if (Settings.getPrimaryResumeFilename(context) == filename) {
            val nextImported = resumesDir(context)
                .listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
                ?.firstOrNull()
            Settings.setPrimaryResumeFilename(context, nextImported?.name)
        }
    }

    // The bytes the pipeline actually attaches/scores against: the primary imported
    // resume if one exists, otherwise the bundled APK asset as a working default.
    fun loadPrimaryResumeBytes(context: Context): ByteArray {
        val primary = Settings.getPrimaryResumeFilename(context)
        if (primary != null) {
            val file = File(resumesDir(context), primary)
            if (file.exists()) return file.readBytes()
        }
        return loadBundledResumeBytes(context)
    }

    // Text extraction (for Gemini prompts / ATS scoring) is separate from the raw bytes
    // above — the attachment is always the original, unmodified bytes; only the
    // extracted text ever feeds into prompts, matching src/resume/loadMasterResume.ts +
    // src/emailJob.ts's split between "attach verbatim" and "read for scoring/prompting".
    fun extractResumeText(bytes: ByteArray): String {
        PDDocument.load(bytes).use { document ->
            return PDFTextStripper().getText(document)
        }
    }
}
