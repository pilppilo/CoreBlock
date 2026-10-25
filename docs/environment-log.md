# CoreBlock — Environment & Install Record

Everything this project downloads or installs on this PC is recorded here, alongside the
toolchain requirements it depends on.

**Rules**

1. Nothing is installed or downloaded for this project without a dated entry in §5.
2. PC-level installs default to portable (unzipped into `C:\Users\weaka\coreblock-tools\`)
   — no PATH, registry, or system-config changes without explicit approval.
3. Downloads are version-pinned, one-off, from official sources only
   (services.gradle.org, dl.google.com, adoptium.net).
4. `git diff docs/environment-log.md` shows exactly what changed in the environment
   between commits.

## 1. Current PC inventory (discovered 2026-10-24, read-only scan)

| Component | Version | Location | Status |
|---|---|---|---|
| JDK | Microsoft OpenJDK **21.0.12.1 LTS** | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\` (`JAVA_HOME` set) | already installed — reuse, nothing to install |
| Android SDK | platforms: **android-37.0** · build-tools: **36.0.0** · platform-tools (adb) · cmdline-tools (`latest`) · SDK licenses accepted | `C:\Users\weaka\AppData\Local\Android\Sdk` | already installed — reuse |
| Android Studio | present (bundles its own JBR) | `C:\Program Files\Android\Android Studio` | informational |
| Git | 2.55.0.windows.2 | system PATH | already installed |
| Gradle | 8.13 | bootstrap: `C:\Users\weaka\coreblock-tools\gradle-8.13\` · wrapper dist: `C:\Users\weaka\.gradle\wrapper\dists\` | installed 2026-10-24 — see §5 |
| winget | v1.29.380 | system | available if ever needed |

Disk at scan time: **C: 200 GB total, 11 GB free (95% used)**. After installs:
**39 GB free (81% used)** — this project added ~1.6 GB (§5); the larger change came from
disk cleanup done outside this session. See §6.

## 2. Project toolchain requirements (pinned)

| Requirement | Pin | Why |
|---|---|---|
| JDK to run Gradle | 17–21 → **existing system JDK 21** | AGP 8.9+ supports 17–21; nothing new installed |
| Compile JVM target | 17 | `app/build.gradle.kts` (`kotlinOptions.jvmTarget`) |
| Gradle | **8.13** | runs on JDK 21; satisfies AGP 8.9.x (needs ≥ 8.11.1) |
| Android Gradle Plugin | **8.9.1** (bump from 8.8.0) | official match for `compileSdk 36` (8.8.0 warns) |
| Kotlin / Compose compiler plugin | 2.1.0 | as configured |
| KSP | 2.1.0-1.0.29 | as configured |
| compileSdk / targetSdk / minSdk | 36 / 36 / 30 | plan.md §11 |
| Android platform package | `platforms;android-36` | installed 2026-10-24, 131 MB ✅ |
| Build tools | 36.0.0 | already installed ✅ |
| Unit testing | JUnit 4.13.2 + kotlinx-coroutines-test 1.9.0 | fetched 2026-10-24 ✅ |
| Emulator system image | `system-images;android-35;google_apis;x86_64` r09 | installed 2026-10-25, 3.5 GB ✅ |

Runtime dependencies (LiteRT 1.1.1, ML Kit GenAI 1.0.0-beta1, Room 2.7.0, Compose BOM
2025.02.00, lifecycle 2.8.7, coroutines 1.9.0) are declared in `app/build.gradle.kts` and
fetched by Gradle into the local Maven cache on first build — logged as a single summary
entry per build in §5.

## 3. Machine-local files (gitignored, never committed)

- `local.properties` → `sdk.dir=C:\Users\weaka\AppData\Local\Android\Sdk`
- Gradle wrapper dists → `C:\Users\weaka\.gradle\wrapper\dists\`

## 4. Install manifest — APPROVED & EXECUTED 2026-10-24

(Approved via in-session confirmation: "Approve all 4". Actual sizes and results in §5.
Total new disk used by this project: **~1.6 GB**.)

| # | Action | Source | Size (approx) | Destination | Why |
|---|---|---|---|---|---|
| 1 | Download `gradle-8.13-bin.zip` (one-time bootstrap) | services.gradle.org/distributions | ~140 MB zip / ~340 MB unpacked | `C:\Users\weaka\coreblock-tools\gradle-8.13\` | needed once to generate the project wrapper; kept as offline fallback (deletable) |
| 2 | Wrapper-managed fetch of Gradle 8.13 | services.gradle.org/distributions | ~140 MB zip / ~340 MB unpacked | `C:\Users\weaka\.gradle\wrapper\dists\` | standard wrapper behavior; what every build thereafter uses |
| 3 | `sdkmanager "platforms;android-36"` | dl.google.com | ~60 MB | `<SDK>\platforms\android-36` | `compileSdk 36` |
| 4 | First-build Maven/Google artifacts (AGP, Kotlin, Compose, Room, LiteRT, ML Kit) | repo.maven.apache.org + dl.google.com | ~0.5–1 GB | `C:\Users\weaka\.gradle\caches\` | automatic on first build; summary entry logged in §5 |

**Total new disk: ~1.2–1.7 GB of the 11 GB free.**

Accompanying in-repo changes (no downloads): AGP `8.8.0 → 8.9.1` in root
`build.gradle.kts`; new `gradlew`, `gradlew.bat`, `gradle/wrapper/*`; `.gitignore`;
`local.properties`.

## 5. Download & install log

| Date | Action | Version | Size | Source | Destination | Result |
|---|---|---|---|---|---|---|
| 2026-10-24 20:xx | Environment scan (read-only) | — | — | — | — | inventory captured in §1 |
| 2026-10-24 22:05 | Download `gradle-8.13-bin.zip` + official `.sha256`; unpack | Gradle 8.13 | 131 MB zip / 146 MB unpacked | services.gradle.org/distributions | `coreblock-tools\` | ✅ SHA-256 verified: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` |
| 2026-10-24 22:08 | `sdkmanager --install "platforms;android-36"` | platform-36 r02 | 131 MB unpacked | dl.google.com | `…\Android\Sdk\platforms\android-36` | ✅ licenses already on file; non-interactive |
| 2026-10-24 22:11 | `gradle wrapper --gradle-version 8.13` (bootstrap) + first plugin resolution | AGP 8.9.1, Kotlin 2.1.0, Compose plugin 2.1.0, KSP 2.1.0-1.0.29 | (into caches) | repo.maven.apache.org + dl.google.com | project `gradlew*`, `gradle/wrapper/*` + `~\.gradle\caches` | ✅ wrapper generated; accompanying in-repo edits: AGP `8.8.0 → 8.9.1`, new `local.properties` (gitignored) |
| 2026-10-24 22:12 | Wrapper-managed Gradle dist download | Gradle 8.13 | 146 MB unpacked | services.gradle.org/distributions | `~\.gradle\wrapper\dists` | ✅ automatic (manifest row 2) |
| 2026-10-24 22:12 | `assembleDebug` attempt #1 | — | — | — | — | ❌ FAILED: project had no `gradle.properties` → `android.useAndroidX` missing. Fixed by adding standard `gradle.properties` (in-repo, no download) |
| 2026-10-24 22:18 | `assembleDebug` attempt #2 — full dependency fetch | Compose BOM 2025.02.00, Room 2.7.0, LiteRT 1.1.1, ML Kit GenAI 1.0.0-beta1 + transitive deps | caches total 1005 MB | repo.maven.apache.org + dl.google.com | `~\.gradle\caches` | ✅ **BUILD SUCCESSFUL in 2m 30s** (36 tasks) → `app\build\outputs\apk\debug\app-debug.apk` (53.8 MB) |
| 2026-10-24 22:35 | Test dependencies added & fetched | junit 4.13.2, kotlinx-coroutines-test 1.9.0 | ~2 MB | repo.maven.apache.org | `~\.gradle\caches` | ✅ for pure-JVM unit tests; 17/17 tests pass (`testDebugUnitTest`), `assembleDebug` still green (43 tasks) |
| 2026-10-25 11:0x | `sdkmanager --install "system-images;android-35;google_apis;x86_64"` (user-approved manifest) | x86_64-35 r09 | 3.5 GB unpacked | dl.google.com | `…\Android\Sdk\system-images\` | ✅ for the emulator test device (nested virt enabled on host first) |
| 2026-10-25 11:0x | Created AVD `CoreBlockTest` (Pixel 6 profile) + first boot | API 35 | ~8 GB runtime disk image | `~\.android\avd\CoreBlockTest.avd\` | ✅ booted in ~4 min; software rendering (swiftshader) inside Hyper-V guest |
| 2026-10-25 11:2x | End-to-end dry-run verification on emulator | — | — | — | — | ✅ listener bound by system; posted promo classified `PROMOTIONAL 90% via rules` → logged `DRY_RUN`, notification left untouched in shade; dashboard shows both decisions; app process stable (only unrelated emulator ANRs under software rendering) |

## 6. Notes & constraints

- **Disk:** C: was 95% full (11 GB free) at scan time; now 39 GB free — ~1.6 GB of that
  is this project (§5), the rest was cleanup outside this session. Emulator system
  images (1.5–10 GB each) are now feasible; the physical Pixel 11 Pro is still the
  preferred test target.
- No system PATH, registry, or environment-variable changes are planned. The existing
  JDK 21 and Android SDK are reused in place.
- If a system-level install ever becomes unavoidable, it appears here first as a
  manifest row before it runs.
