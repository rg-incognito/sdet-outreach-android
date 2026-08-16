@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.rohitgande.sdetoutreach

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rohitgande.sdetoutreach.data.Settings
import com.rohitgande.sdetoutreach.resume.ResumeStore
import com.rohitgande.sdetoutreach.resume.StoredResume
import com.rohitgande.sdetoutreach.ui.theme.SdetOutreachTheme

// Everything here was previously either hardcoded (scrape queries, maxJobsPerRun) or
// baked into BuildConfig at build time (candidate name/phone/CTC/notice period/location,
// resume file). Editing it now doesn't need a new APK build — see Settings.kt/ResumeStore.kt.
// Credentials (Gemini/Gmail/Sheets) are intentionally NOT here; they stay in BuildConfig.
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdetOutreachTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "resume.pdf"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            cursor.getString(idx)?.let { name = it }
        }
    }
    return name
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val queries = remember { mutableStateListOf<String>().apply { addAll(Settings.getQueries(context)) } }
    var maxJobsPerRun by remember { mutableStateOf(Settings.getMaxJobsPerRun(context).toString()) }

    val profile = Settings.getCandidateProfile(context)
    var name by remember { mutableStateOf(profile.name) }
    var phone by remember { mutableStateOf(profile.phone) }
    var currentCTC by remember { mutableStateOf(profile.currentCTC) }
    var expectedCTC by remember { mutableStateOf(profile.expectedCTC) }
    var noticePeriod by remember { mutableStateOf(profile.noticePeriod) }
    var location by remember { mutableStateOf(profile.location) }

    val resumes = remember { mutableStateListOf<StoredResume>().apply { addAll(ResumeStore.listResumes(context)) } }
    fun refreshResumes() {
        resumes.clear()
        resumes.addAll(ResumeStore.listResumes(context))
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            ResumeStore.importResume(context, uri, displayName)
            refreshResumes()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "Scrape queries",
                description = "Boolean LinkedIn search queries — one per box. Each is run separately every scrape.",
            ) {
                queries.forEachIndexed { index, query ->
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { queries[index] = it },
                            modifier = Modifier.weight(1f),
                            minLines = 2,
                        )
                        IconButton(onClick = { queries.removeAt(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove query")
                        }
                    }
                }
                OutlinedButton(onClick = { queries.add("") }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Add query")
                }
                OutlinedTextField(
                    value = maxJobsPerRun,
                    onValueChange = { if (it.all(Char::isDigit)) maxJobsPerRun = it },
                    label = { Text("Max jobs per run") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(
                title = "Your profile",
                description = "Used to fill in the outreach emails and to decide when CTC/notice period/location get mentioned.",
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = currentCTC, onValueChange = { currentCTC = it }, label = { Text("Current CTC") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = expectedCTC, onValueChange = { expectedCTC = it }, label = { Text("Expected CTC") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = noticePeriod, onValueChange = { noticePeriod = it }, label = { Text("Notice period") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
            }

            SectionCard(
                title = "Resumes",
                description = "The starred resume is the one attached and scored against every outreach email. Import as many as you like.",
            ) {
                resumes.forEach { resume ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(onClick = {
                            ResumeStore.setPrimary(context, if (resume.isBundled) null else resume.filename)
                            refreshResumes()
                        }) {
                            Icon(
                                if (resume.isPrimary) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (resume.isPrimary) "Primary resume" else "Set as primary",
                                tint = if (resume.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            if (resume.isBundled) {
                                "Default resume (bundled) — ${resume.sizeBytes / 1024} KB"
                            } else {
                                "${resume.filename} (${resume.sizeBytes / 1024} KB)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!resume.isBundled) {
                            IconButton(onClick = {
                                ResumeStore.delete(context, resume.filename)
                                refreshResumes()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete resume")
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import resume (PDF)")
                }
            }

            Button(
                onClick = {
                    Settings.setQueries(context, queries.filter { it.isNotBlank() })
                    Settings.setMaxJobsPerRun(context, maxJobsPerRun.toIntOrNull() ?: Settings.defaultMaxJobsPerRun)
                    Settings.setCandidateProfile(
                        context,
                        Settings.CandidateProfile(
                            name = name,
                            phone = phone,
                            currentCTC = currentCTC,
                            expectedCTC = expectedCTC,
                            noticePeriod = noticePeriod,
                            location = location,
                        ),
                    )
                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save changes")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}
