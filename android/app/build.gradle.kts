import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

// Wraps a possibly-absent secret as a Kotlin/Java string literal safe to splice into
// generated BuildConfig source. Properties.load() already unescapes literal "\n" into
// real newlines while parsing secrets.properties (the PEM private key spans multiple
// lines) — re-escape those as source-level "\n" so the generated BuildConfig.java is
// valid, single-line source; the compiler turns it back into a real newline in the
// resulting String constant, so BuildConfig.SERVICE_ACCOUNT_PRIVATE_KEY needs no further
// unescaping at runtime.
fun secretLiteral(key: String): String {
    val value = secrets.getProperty(key, "")
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
    return "\"$escaped\""
}

android {
    namespace = "com.rohitgande.sdetoutreach"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rohitgande.sdetoutreach"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "GEMINI_API_KEY", secretLiteral("geminiApiKey"))
        buildConfigField("String", "GMAIL_USER", secretLiteral("gmailUser"))
        buildConfigField("String", "GMAIL_APP_PASSWORD", secretLiteral("gmailAppPassword"))
        buildConfigField("String", "SHEETS_SPREADSHEET_ID", secretLiteral("sheetsSpreadsheetId"))
        buildConfigField("String", "SERVICE_ACCOUNT_EMAIL", secretLiteral("serviceAccountEmail"))
        buildConfigField("String", "SERVICE_ACCOUNT_PRIVATE_KEY", secretLiteral("serviceAccountPrivateKey"))
        buildConfigField("String", "CANDIDATE_NAME", secretLiteral("candidateName"))
        buildConfigField("String", "CANDIDATE_PHONE", secretLiteral("candidatePhone"))
        buildConfigField("String", "CANDIDATE_CURRENT_CTC", secretLiteral("candidateCurrentCTC"))
        buildConfigField("String", "CANDIDATE_EXPECTED_CTC", secretLiteral("candidateExpectedCTC"))
        buildConfigField("String", "CANDIDATE_NOTICE_PERIOD", secretLiteral("candidateNoticePeriod"))
        buildConfigField("String", "CANDIDATE_LOCATION", secretLiteral("candidateLocation"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE.txt",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
}
