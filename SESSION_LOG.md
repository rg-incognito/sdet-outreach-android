# Android/Termux setup — session log

A record of how this copy of the project was ported to run natively on an Android phone
via Termux, including the problems hit along the way and how each was resolved. The
step-by-step instructions live in `TERMUX_SETUP.md`; this file is the narrative of what
actually happened getting there.

## Goal

Run the full pipeline (LinkedIn scrape via Playwright → Gemini outreach generation →
Gmail send) natively on an Android phone, on the same 9 AM / 6 PM schedule as the laptop,
without the laptop needing to be on.

## Why not just install Node in Termux directly

Termux's own userland uses Android's bionic libc, not glibc. Node itself runs fine
natively in Termux, but Playwright downloads a prebuilt Chromium binary built for glibc
Linux, which won't run under Termux directly. The fix: run a real Linux distro (Ubuntu)
inside Termux via `proot-distro`, and do everything — Node, Playwright, Chromium — inside
that container instead.

## Code changes (this folder only, not the laptop copy)

Headless Chromium's own sandbox needs Linux namespaces that `proot` can't provide (it's
ptrace-based, not real kernel namespaces). Added
`--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage` to the three
`chromium.launch()` call sites (`src/pipeline.ts`, `src/scrapers/linkedin.ts`,
`src/resume/renderPdf.ts`). These flags are specific to this Android copy — the laptop
copy doesn't need them and doesn't have them.

## Getting the project onto the phone

Tried `adb push` first. First attempt hung for 200+ seconds of CPU time and went nowhere
— the cause was Git Bash's automatic path conversion, which rewrites any argument that
looks like a POSIX path (e.g. `/sdcard/Download/...`) into a Windows path before handing
it to a native `.exe` like `adb`. Fixed by setting `MSYS_NO_PATHCONV=1` for the remote
(device-side) path, and passing the local (Windows) source path in native
`C:\Users\...\` form instead of MSYS `/c/Users/...` form so it wouldn't need conversion
in the first place. Once both sides were correctly formed, `adb push` moved all 190
project files (~18 MB) in under 8 seconds.

## Environment already further along than expected

By the time cron/Playwright setup started, `apt install` reported Node 20, git,
`make`/`g++`, and every Chromium runtime library already present — someone (or a prior
session) had already provisioned this proot fairly completely. Only `unzip` and `cron`
needed installing.

One Ubuntu-24.04-specific gotcha worth remembering: several Chromium runtime deps were
renamed with a `t64` suffix (the `time_t64` transition), and `libasound2` became a
virtual package with no default candidate — the fix was using the `t64`-suffixed package
names (`libatk1.0-0t64`, `libasound2t64`, etc.) instead of the names from older guides.

## Driving Termux remotely via adb

Typing long commands on a phone keyboard is painful, so instead of asking for manual
input, `adb shell input text '...'` + `adb shell input keyevent 66` (Enter) was used to
type directly into the focused Termux session, with `adb shell screencap` + `adb pull` to
see the result after each step (screenshots read back as images).

The one recurring hazard: `adb shell <args>` re-joins its arguments and runs them via the
**device's own shell** before they ever reach `input text`. Any shell metacharacter in
the payload (`&&`, `|`, `>`, `"`, `#` at the start of a word) gets interpreted by that
intermediate shell instead of being typed literally — one early attempt
(`cd ~/SDETOutreach && ls`) broke exactly this way, executing `cd` and silently discarding
`&& ls`. The reliable fix used from then on: wrap the entire payload in single quotes as
part of the local command, e.g. `adb shell "input text 'PAYLOAD'"` — single quotes
protect literally everything inside them from the device shell, so the *only* thing
`input` itself ever needs is `%s` in place of literal spaces (its own separate
convention, unrelated to the quoting problem).

A USB disconnect mid-run (adb briefly reporting zero devices, even after
`adb kill-server`) turned out to be transient — a `kill-server` + `start-server` + wait
recovered it. The pipeline run itself kept going the whole time, since it's a background
process on the phone with no dependency on the USB/adb link staying up.

## First successful dry run

`npm run run-all -- --dry-run` completed end to end: launched Chromium, loaded the copied
`data/storageState.json` LinkedIn session, scraped 50 raw postings across both search
queries, scored each against the resume, generated real outreach subject lines via
Gemini for several recruiter contacts, and correctly sent zero real emails (dry-run).
Confirmed the whole stack — Node, Playwright, Chromium, the LinkedIn session, Gemini,
Google Sheets — works unmodified inside the proot.

## Scheduling: cron inside a proot has no init system to keep it alive

`service cron start` only starts cron for the lifetime of whatever shell launched it —
proot has no real PID 1, so the crond daemon dies the moment the `proot-distro login`
session that started it exits. Fixed by launching the *entire* proot login as one
long-lived detached background job from plain Termux, with cron started as part of it:

```sh
nohup proot-distro login ubuntu -- bash -c "service cron start && tail -f /dev/null" > ~/cron-proot.log 2>&1 &
disown
```

`tail -f /dev/null` is a deliberate never-exits no-op whose only job is to keep that
outer proot process (and therefore cron, running inside it) alive indefinitely.
`nohup` + `&` + `disown` detach the whole thing from the terminal so it survives closing
the Termux session. Verified via `ps -ef` showing `/usr/sbin/cron` with **PPID 1**
(properly re-parented/daemonized, not a zombie tied to the login shell).

The crontab entry itself: `0 9,18 * * * cd /root/SDETOutreach && /usr/bin/npm run run-all
>> /root/SDETOutreach/logs/cron.log 2>&1` — set via `echo "..." | crontab -` rather than
the interactive `crontab -e`, to avoid driving `nano`/`vi` through the same adb-input
technique.

`termux-wake-lock` (run once in plain Termux, not inside proot) tells Android not to
fully sleep the CPU while Termux is backgrounded — confirmed via the persistent
"Termux — 1 session (wake lock held)" system notification.

## Termux:Boot: surviving a phone reboot

Everything above dies on reboot (the disowned background job is gone). Termux:Boot is
the add-on that lets a script run automatically on every boot.

**Signing mismatch**: the first install attempt used Termux:Boot's GitHub release APK
and failed with `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: ... has no signatures that
match those in shared user com.termux`. Termux add-ons share Android's `sharedUserId`
mechanism with the base Termux app, which requires an identical signing key — this
phone's Termux came from F-Droid, so the add-on has to be the F-Droid build too, not
GitHub's. Fixed by pulling the APK from F-Droid's own package API
(`https://f-droid.org/api/v1/packages/com.termux.boot`) instead.

**Heredoc escaping bug**: writing `~/.termux/boot/start-cron.sh` via a `cat > file
<<EOF` heredoc (typed in through the same adb `input text` mechanism), an earlier habit
of escaping embedded double quotes as `\"` leaked literal backslashes into the file,
because those quotes were already inside an outer single-quoted payload and didn't need
escaping a second time. Fixed by re-typing the heredoc with plain `"` and verifying via
`cat` that the file held real double quotes, not `\"`.

**History expansion**: the script's `#!/data/data/...` shebang line, typed as literal
interactive input, is exactly the shape bash's history expansion looks for (`!` followed
by a non-blank character) — `set +H` before typing the heredoc avoided a mangled
`event not found` line.

Final script (`~/.termux/boot/start-cron.sh`, verified executable, 169 bytes):
```sh
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
nohup proot-distro login ubuntu -- bash -c "service cron start && tail -f /dev/null" > ~/cron-proot.log 2>&1 &
```

## Known remaining gap

OEM-specific "Autostart" toggles (Xiaomi/MIUI, OnePlus, etc.) sit outside stock Android's
battery-optimization settings and can silently prevent Termux:Boot's receiver from firing
even when everything above is installed correctly. This has to be checked manually per
device in Settings → Apps, and isn't something drivable via adb generically.
