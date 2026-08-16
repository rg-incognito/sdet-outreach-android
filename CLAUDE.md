# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Automated pipeline that scrapes LinkedIn posts asking for Automation Test Engineer / SDET
candidates, filters them, and emails a personalized (Gemini-generated) outreach message
with the candidate's resume attached. This is the Android/Termux fork of a Windows-native
project — see README.md's top for the fork relationship, `TERMUX_SETUP.md` for how it's
set up on-device, and `SESSION_LOG.md` for the real setup history/gotchas. The only code
difference from the Windows version is `--no-sandbox --disable-setuid-sandbox
--disable-dev-shm-usage` on the three Chromium launch sites (`pipeline.ts`,
`scrapers/linkedin.ts`, `scripts/linkedin-login.ts`), required to run headless Chromium
inside a Termux `proot` container.

Read README.md before making non-trivial changes — it documents the Tracker
sheet's dedup/retry semantics, the Gemini model fallback chain, and the full config/env
reference in detail; this file only covers what's needed to navigate and run the code.

## Commands

```
npm run dev                    # tsx src/index.ts — send-only, no build step
npm run build                  # tsc -p tsconfig.json -> dist/
npm start                      # node dist/index.js (requires build first)

npm run linkedin-login         # one-time interactive login, saves data/storageState.json
npm run scrape-to-sheet        # scrape only, writes to the Posts sheet
npm run run-all                # scrape -> tailor -> send, one process (the normal entry point)
npm run run-all -- --dry-run   # same, but skips real email + Tracker sheet writes
npm run migrate-xlsx-to-sheets # one-time: import old local data/tracker.xlsx into the Google Sheet
```

There is no test suite and no lint script configured in `package.json`. Type-check via
`npm run build` (runs `tsc` with `strict: true`).

Manual/scheduled triggers: `scripts/run-now.bat` (Windows, double-click or CLI) and
`scripts/register-task.ps1` (Windows Scheduled Task) run the laptop side;
`scripts/termux-cron-status.sh` inspects the phone-side cron job (see `SESSION_LOG.md` /
`TERMUX_SETUP.md` for how that cron job itself is installed).

## Architecture

Two independently runnable halves, chained together by `scripts/run-all.ts`:

1. **Scrape** (`src/scrapers/linkedin.ts`, driven by `src/scrapeToPosts.ts`) — runs each
   `config.json` → `search.queries` boolean query against LinkedIn's post/content search
   (infinite-scroll, not real pagination — `MAX_LOAD_MORE_ATTEMPTS` in `linkedin.ts` bounds
   how far it scrolls per query toward `search.maxJobsPerRun`). Requires the local
   `data/storageState.json` session (from `npm run linkedin-login`) and must run on a
   trusted IP. Extracted posts are filtered by `src/filters/extractEmail.ts` (must contain
   a usable email), `src/utils/isGenericMailbox.ts` (blocklists non-recruiting mailboxes),
   and `src/gemini/isOpportunityPost.ts` (Gemini call that drops posts not actually
   offering a role — fails open on Gemini error). Survivors are upserted into the **Posts**
   sheet, deduped by Job URL.

2. **Send** (`src/pipeline.ts`, the only thing `src/index.ts` runs) — reads the Posts
   sheet, dedups against every recruiter email already in the **Tracker** sheet
   (`Status: Sent` rows are skipped; `Failed` rows are retried), then for each pending row:
   scores the resume against the post via `src/resume/atsScore.ts`, generates an email via
   `src/gemini/generateOutreach.ts`, and sends it via `src/mailer/sendMail.ts`
   (`--dry-run` short-circuits the actual send). `src/emailJob.ts` orchestrates this
   per-post and always upserts a Tracker row (`Sent` or `Failed`, with whatever
   subject/body/error was produced) — a send is never silently lost. Tracker upsert is by
   recruiter email, so a later successful retry updates the existing row instead of adding
   a duplicate.

Both halves share `src/sheets/client.ts` (authenticated `google-spreadsheet` doc, cached
per process) and read config through `src/config.ts` (validates `config.json` +
`.env` with `zod`, exposed as the typed `Config` in `src/types.ts`).

Other notable pieces:
- `src/gemini/client.ts` — model fallback chain (env override → env fallback override →
  every other `generateContent`-capable model on the key via `ai.models.list()`), so a
  per-model daily quota exhaustion doesn't fail the run.
- `src/resume/` — resume loading/scoring/PDF rendering. The resume attachment is never
  AI-rewritten; only the outreach email copy is generated. A `.pdf` `masterResumePath` is
  attached byte-for-byte; other formats are rendered to PDF via `renderPdf.ts` with
  unmodified content.
- `src/store/auditLog.ts` — appends every real send to `logs/audit.log` (JSON lines),
  independent of the Tracker sheet.

## Config and secrets

- `config.json` — search queries, `masterResumePath`, candidate identity fields
  (`candidateName`/`Phone`/`CurrentCTC`/`ExpectedCTC`/`NoticePeriod`/`Location` — CTC/notice
  period/location are only worked into an outreach email when the source post explicitly
  asks for them). Not secret, safe to edit directly.
- `.env` (gitignored; copy from `.env.example`) — `GEMINI_API_KEY`, `GMAIL_USER`,
  `GMAIL_APP_PASSWORD`, `GOOGLE_SHEETS_SPREADSHEET_ID`, `GOOGLE_SERVICE_ACCOUNT_EMAIL`,
  `GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY` (pasted as-is with literal `\n`s — `src/config.ts`
  unescapes them). `GMAIL_APP_PASSWORD` is the only var allowed to be empty (needed only
  for real, non-dry-run sends).
- Storage: Posts/Tracker data lives in an online Google Sheet (service account auth, no
  interactive login) — not a local file. `data/storageState.json` (LinkedIn session) and
  `data/tracker.xlsx` (legacy, pre-migration) are both gitignored.

## Native Android app (`android/`)

A full Kotlin rewrite living alongside the Node tool (untouched) in `android/` — replaces
the Termux/proot/Playwright setup with a real app, solving the on-device LinkedIn-login
problem via an in-app `WebView` (session cookies persist automatically; no more copying
`storageState.json` from the laptop). Built for the candidate herself (Vaishnavi Salve) to
run directly — app label "Nahi App", custom launcher icon, no mention of "SDET Outreach" in
the UI. Plan/design history: `C:\Users\rohit\.claude\plans\wiggly-knitting-reef.md`.

- **Toolchain** (none of this is on PATH by default in this environment): JDK 17 at
  `C:\Users\rohit\AppData\Local\Java\jdk-17.0.20+8`, Android SDK at
  `C:\Users\rohit\AppData\Local\Android\Sdk`. Build with
  `JAVA_HOME=<jdk path> ./gradlew.bat assembleDebug` from `android/`. Debug APK for sharing
  gets copied to `C:\Users\rohit\Desktop\NahiApp.apk` (gitignored, rebuild+recopy after
  changes — see root `.gitignore`).
- **UI**: Jetpack Compose + Material 3 (dynamic color), not the original XML/ViewBinding
  layouts — those were deleted. Screens: `MainActivity` (dashboard, single "Run Now" button
  with a dry-run switch), `LoginActivity` (WebView), `RunActivity` (the unified scrape+send
  flow, foreground WebView + live log), `AdvancedActivity` (debug-only: scrape-only,
  send-only, SMTP self-test — `TestScrapeActivity`/`SendPipelineActivity` were merged into
  this), `BackgroundActivity` (permissions + schedule toggle), `SettingsActivity` (scrape
  queries, candidate profile, resume manager). `pipeline/RunNow.kt` is the shared
  scrape→Posts-sheet→send orchestration used by both `RunActivity` and the worker, so they
  can't drift apart.
- **Runtime-configurable settings** (`data/Settings.kt`, plain `SharedPreferences`): scrape
  queries and `maxJobsPerRun` (previously hardcoded), and candidate profile fields
  (name/phone/CTC/notice period/location — previously `BuildConfig`-only). All fall back to
  the original hardcoded/`BuildConfig` defaults on first run. Edited from `SettingsActivity`.
- **Resumes** (`resume/ResumeStore.kt`): multiple PDFs can be imported (`ACTION_OPEN_DOCUMENT`
  picker) into `filesDir/resumes/`; one is "primary" (`Settings.primaryResumeFilename`,
  `null` = the bundled APK-asset default) and is what gets attached + scored. The bundled
  default is always listed alongside imports (not just when the list is empty) so it stays
  selectable. V1 scope unchanged: `.pdf` only, no markdown/docx rendering.
- **Secrets**: `android/secrets.properties` (gitignored, mirrors the root `.env` +
  `config.json`) is read by `app/build.gradle.kts` and exposed as `BuildConfig` fields —
  Gemini key, Gmail address/App Password, and the Sheets service-account key stay here
  **by deliberate choice**, not moved to an in-app encrypted-prefs screen. This means
  they're recoverable by decompiling any APK built from this `secrets.properties`,
  including the one shared with the candidate — accepted tradeoff for a personal/trusted
  share, revisit if the APK's distribution ever widens.
  `android/app/src/main/assets/resume.pdf` (gitignored) is the bundled fallback resume.
- **Package-for-package port**: every module under `android/app/src/main/java/com/rohitgande/sdetoutreach/`
  mirrors a Node file 1:1 (e.g. `gemini/GenerateOutreach.kt` ↔ `src/gemini/generateOutreach.ts`,
  `sheets/TrackerSheet.kt` ↔ `src/sheets/trackerSheet.ts`) — check the Node original first
  when changing behavior, then port the change to keep both in sync.
  `scraper/LinkedInScraper.kt` ports the Playwright scraper to WebView JS injection
  (`evaluateJavascript`), validated live against real LinkedIn.
- **Background execution**: `worker/ScrapeAndOutreachWorker.kt` (WorkManager
  `CoroutineWorker` + foreground service) drives `scraper/OverlayWebViewHost.kt`, an
  invisible `TYPE_APPLICATION_OVERLAY` WebView — proven working with zero foreground UI.
  Requires the user to grant "Display over other apps" and battery-optimization exemption
  once via the in-app Background screen before scheduling.
- Google Sheets auth is hand-rolled (`sheets/SheetsAuth.kt`: RS256 JWT + OAuth2 token
  exchange via OkHttp) rather than pulling in `google-api-client`; Gmail sending is
  JavaMail SMTP + App Password (`mail/SmtpMailer.kt`), a direct port of the Node
  `nodemailer` setup.
- **WorkManager can silently resurrect a cancelled periodic job across a reinstall/update**
  (observed live: explicitly disabling background runs, then reinstalling the APK, brought
  the ~2h periodic job back with a fresh job ID — and it fired for real, sending one live
  email, before this was caught). Fixed via `Settings.isBackgroundEnabled` — an explicit
  on/off flag independent of WorkManager's own state, re-asserted in
  `SdetOutreachApp.onCreate()` by unconditionally calling `cancelPeriodicRun()` whenever
  it's false. `BackgroundActivity`'s Enable/Disable buttons keep this flag in sync. Any
  future code that touches scheduling must go through this flag, not call
  `schedulePeriodicRun`/`cancelPeriodicRun` directly, or the resurrection bug comes back.
- The periodic background schedule is currently **disabled**. Enabling it means two
  independent automations (this app + the phone's existing Termux cron, if still active)
  hit the same LinkedIn account/Sheet every ~2h — decide deliberately before turning it on.

## Working in this codebase

- ESM throughout (`"type": "module"`) — internal imports use explicit `.js` extensions
  even though source files are `.ts` (NodeNext resolution).
- Every real email send is irreversible and unattended (no human-review step) — prefer
  `--dry-run` when testing changes to scraping, filtering, scoring, or email generation.
- When touching Chromium launch args, keep the three launch sites
  (`pipeline.ts`, `scrapers/linkedin.ts`, `scripts/linkedin-login.ts`) in sync — the
  `--no-sandbox` flags are required for this fork to run under Termux's `proot` container.
