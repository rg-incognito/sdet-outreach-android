# Running this project on Android via Termux

## Why this isn't a plain `pkg install nodejs` job

Termux's own userland uses Android's **bionic** libc, not glibc. Node itself runs fine
natively in Termux, but Playwright downloads a prebuilt **Chromium** binary built for
glibc Linux — it will not run directly under Termux. The fix is to run a real Linux
distro (Ubuntu) inside Termux via `proot-distro`, and do everything (Node, Playwright,
Chromium) inside that.

Expect this to be slower and heavier on battery than your laptop. Headless Chromium
wants real RAM/CPU — this works best on a phone with 6GB+ RAM. Treat it as an
experiment, not a guaranteed drop-in replacement.

---

## 1. Install Termux (F-Droid build, not Play Store)

The Play Store build of Termux is unmaintained/broken for this kind of use. Install
from F-Droid: https://f-droid.org/packages/com.termux/

Optional but recommended companion apps (also from F-Droid):
- **Termux:Boot** — lets a script auto-run when the phone reboots
- **Termux:API** — lets scripts trigger Android notifications, wake locks, etc.

## 2. First-time Termux setup

```sh
pkg update && pkg upgrade -y
termux-setup-storage        # grants access to /storage/emulated/0 (shared storage)
pkg install proot-distro -y
```

## 3. Install Ubuntu inside Termux

```sh
proot-distro install ubuntu
proot-distro login ubuntu
```

`proot-distro login ubuntu` drops you into an Ubuntu ARM64 shell. Run everything below
**inside** that shell unless noted otherwise.

## 4. Install Node, git, and Playwright's system libraries

```sh
apt update && apt upgrade -y
apt install -y curl git python3 make g++

# NodeSource setup for a recent Node LTS
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs

node -v && npm -v
```

Playwright's `npx playwright install-deps` mostly assumes a real systemd host and may
not complete cleanly under proot — install the Chromium runtime deps manually instead:

```sh
apt install -y libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
  libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
  libgbm1 libasound2 libpango-1.0-0 libcairo2 libatspi2.0-0
```

On Ubuntu 24.04 (the `time_t64` transition), several of these were renamed with a
`t64` suffix and `libasound2` became a virtual package with no default candidate —
if the command above errors, use this instead:

```sh
apt install -y libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
  libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
  libgbm1 libasound2t64 libpango-1.0-0 libcairo2 libatspi2.0-0t64
```

(More generally: if `apt install` says a package name doesn't exist, run
`apt search <partial-name>` — minor version suffixes shift between Ubuntu releases.)

## 5. Copy the project in

First zip the folder on Windows and copy it to the phone's `Download` folder via USB
(File Transfer/MTP mode) or cloud sync — this part happens outside Termux entirely.

Then, from inside the Ubuntu proot, check whether shared storage is directly visible:

```sh
proot-distro login ubuntu
ls /storage/emulated/0/Download
```

If the zip shows up there, this is all you need:
```sh
apt install -y unzip
unzip /storage/emulated/0/Download/SDETOutreach-Android.zip -d ~/SDETOutreach
```

**If that path is empty or missing** (varies by device/proot-distro version), don't
fight it — pull the file over HTTP via loopback instead, which always works since proot
doesn't sandbox networking:

In a plain Termux session (swipe from the left edge → "New session" if you're currently
inside the proot shell):
```sh
pkg install python -y
cd ~/storage/shared/Download
python3 -m http.server 8080
```
Leave that running, then in your other (proot) session:
```sh
apt install -y curl unzip
curl -O http://127.0.0.1:8080/SDETOutreach-Android.zip
unzip SDETOutreach-Android.zip -d ~/SDETOutreach
```
Ctrl+C the `http.server` session once the download finishes.

Alternatively, `git clone` your GitHub repo directly inside the Ubuntu proot (network
access always works) and copy `.env`, `config.json`, `data/storageState.json`, and the
resume PDF over separately via the same method — those are gitignored, so a plain clone
won't include them.

## 6. Install dependencies and Playwright's browser

```sh
cd ~/SDETOutreach
npm install
npx playwright install chromium
```

This downloads Playwright's Linux ARM64 Chromium build — expect a sizeable download
(~300MB+) and give it time on mobile data/Wi-Fi.

## 7. Sandbox args (already applied in this copy)

Headless Chromium's own sandbox needs Linux namespaces that proot can't provide.
`src/pipeline.ts`, `src/scrapers/linkedin.ts`, and `src/resume/renderPdf.ts` in **this
folder** already launch Chromium with `--no-sandbox --disable-setuid-sandbox
--disable-dev-shm-usage` for exactly this reason. (The main `SDETOutreach` folder on
your laptop does not have this change — it doesn't need it there.)

## 8. Test it

```sh
npm run run-all -- --dry-run
```

Watch for the same kind of output you'd see on the laptop. If Chromium fails to launch
at all, re-check step 4's library list against the actual error message — it'll usually
name the missing `.so` file directly.

## 9. Scheduling (Android has no Task Scheduler)

There's no Windows-Task-Scheduler equivalent baked into Termux. Two practical options:

**A. Simple loop script** (easiest, works entirely inside proot):
```sh
# run-loop.sh
while true; do
  npm run run-all
  sleep 39600   # 11 hours; adjust to taste
done
```
Run it with `termux-wake-lock` held so Android doesn't suspend the process:
```sh
termux-wake-lock
nohup bash run-loop.sh > loop.log 2>&1 &
```
(`termux-wake-lock` requires Termux:API installed, or use the `Acquire Wakelock`
notification action Termux itself exposes when running a long task.)

**B. cron inside the Ubuntu proot** (this is what's actually set up on this phone):

Inside the proot, install cron and set the crontab (no interactive editor needed —
this pipes the line straight in, avoiding `crontab -e`'s nano/vi dependency):
```sh
apt install -y cron
echo "0 */2 * * * cd /root/SDETOutreach && /usr/bin/npm run run-all >> /root/SDETOutreach/logs/cron.log 2>&1" | crontab -
crontab -l   # verify
```
(`0 */2 * * *` runs at the top of every even hour — 12 times a day. This phone's
schedule was deliberately set to run more often than the laptop's 9 AM/6 PM Task
Scheduler job — they're independent schedules, not required to match. To edit later
without disturbing the already-running cron daemon, `proot-distro login ubuntu` again —
a second login into the same rootfs, run `echo "..." | crontab -` again, then `exit` —
the running daemon picks up crontab file changes on its own, no restart needed.)

`service cron start` only starts cron for the current proot session — proot has no real
init system, so a daemon started this way dies the moment the `proot-distro login` shell
that launched it exits. The fix: `exit` back to plain Termux, then launch the whole proot
as a detached background job that stays alive on its own, with cron started inside it:
```sh
exit   # back to plain Termux, out of the ubuntu proot
nohup proot-distro login ubuntu -- bash -c "service cron start && tail -f /dev/null" > ~/cron-proot.log 2>&1 &
disown
```
The `tail -f /dev/null` is a deliberate no-op that never exits — its only job is to keep
the outer `proot-distro login` process (and therefore the whole proot environment,
including the cron daemon running inside it) alive indefinitely. `nohup` + `&` + `disown`
together mean this keeps running even after you close the Termux session or the terminal
app is swiped away from recent apps — it's no longer attached to that terminal at all,
only to the phone's process table.

Verify it's actually running:
```sh
cat ~/cron-proot.log   # should show "Starting periodic command scheduler cron ...done."
ps -ef | grep cron     # should show /usr/sbin/cron with PPID 1 (properly daemonized)
```

Also run `termux-wake-lock` (plain Termux, not inside proot) once — this tells Android
not to fully sleep the CPU while Termux is backgrounded. You'll see a persistent
"Termux — 1 session (wake lock held)" notification confirming it's active. It only needs
to be run once per boot; it isn't tied to any particular session and survives you closing
the terminal.

## 10. Keep it alive in the background

- **Battery optimization**: Android Settings → Apps → Termux → Battery → set to
  "Unrestricted" (exact wording varies by OEM — Samsung/Xiaomi/OnePlus all bury this
  differently and are especially aggressive about killing background apps). Some OEMs
  (Xiaomi/MIUI, OnePlus/OxygenOS, others) also have a separate "Autostart" toggle beyond
  stock Android's battery settings — find it and enable it for both Termux and
  Termux:Boot, or the boot receiver below won't fire even if installed correctly.
- **Termux:Boot** (this is set up and working on this phone): the add-on app that lets a
  script run automatically on every reboot, closing the one gap from section 9 — without
  it, a reboot kills the disowned cron/proot background job and it has to be manually
  restarted.

  Termux:Boot **must be signed with the same key as your Termux install**. If your
  Termux came from F-Droid (as opposed to GitHub releases or Google Play), you need the
  F-Droid build of Termux:Boot too — mixing signers fails install with
  `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`. Get it from F-Droid (search "Termux:Boot" in
  the F-Droid app, or https://f-droid.org/packages/com.termux.boot/), install it, and
  open it once (it just shows a usage screen — opening it is what lets Android register
  its boot receiver).

  Then, in **plain Termux** (not inside the proot), create the boot script:
  ```sh
  mkdir -p ~/.termux/boot
  cat > ~/.termux/boot/start-cron.sh <<EOF
  #!/data/data/com.termux/files/usr/bin/sh
  termux-wake-lock
  nohup proot-distro login ubuntu -- bash -c "service cron start && tail -f /dev/null" > ~/cron-proot.log 2>&1 &
  EOF
  chmod +x ~/.termux/boot/start-cron.sh
  ```
  This is exactly the same wake-lock + persistent-proot-with-cron trick from section 9,
  just packaged so Termux:Boot fires it automatically on every reboot instead of you
  typing it by hand. Termux:Boot runs anything executable it finds in `~/.termux/boot/`,
  in sorted-filename order, once per boot.

  If you're typing this by hand rather than pasting, watch out for the `#!` shebang line
  — interactive bash treats `!` as history expansion by default and will mangle it
  (`event not found`). Run `set +H` first to disable that for the session, or paste the
  whole heredoc block at once.
- Keep the phone plugged in and on Wi-Fi for any long unattended run — same reasoning
  as the laptop setup, just harder to guarantee on a phone you're carrying around.

## Known rough edges

- LinkedIn scraping via Playwright inside proot Chromium is unofficial/unsupported by
  Playwright itself — if something breaks, there's no upstream support path, only
  general Chromium/proot troubleshooting.
- Copying `data/storageState.json` from the laptop reuses your existing LinkedIn
  session (same account, same cookies) — this is different from a fresh login and
  should not look like a new device to LinkedIn from a session standpoint, but the
  underlying Chromium build/version differs from your desktop browser, which is a
  normal, low-risk variance (Playwright's Chromium updates independently of your
  desktop Chrome anyway).
- If Chromium still refuses to launch even with the sandbox args (some Android kernels
  restrict `unshare`/user namespaces further than proot can work around), that's a
  proot/kernel limitation, not something fixable from this codebase.
