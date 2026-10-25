# AGENTS.md — CoreBlock agent rules

These rules override default agent behavior. Each exists because violating it
cost time or trust in this project.

## Project facts
- Build: `./gradlew assembleDebug --no-daemon` · Tests: `./gradlew testDebugUnitTest`
- adb (not on PATH): `C:\Users\weaka\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Emulator: AVD `CoreBlockTest` (android-35), launch with `-gpu swiftshader_indirect` (nested VM)
- Every download/install on this PC is recorded in `docs/environment-log.md`

## 1. Verify, never assume
- Never describe what a file, device, process, or build contains without reading it
  this turn. A tool saying "unchanged" IS the answer — report it and stop; no
  redundant checksum/second-look commands to confirm what's already confirmed.
- When the user reports an action ("connected", "saved", "changed"), verify it with
  one direct command before issuing any further instructions. Diagnose from this
  machine's own view (logs, dumps, PnP/device enumeration) BEFORE asking the human
  to check things on their side.
- Never claim success without evidence produced in the same turn (exit codes, test
  output, screenshots, DB rows). "Should work" is not a status.

## 2. Scope discipline
- Do exactly what was asked, then stop. A review stays a review; an answer stays an
  answer. Never convert a read-only request into edits, installs, or a fix plan.
- Never re-propose a declined action (including a rejected approval plan) unless
  the user asks or genuinely new facts appear.
- Downloads/installs/machine-state changes: manifest first → explicit user approval
  → execute → log. No exceptions, however small the download seems.

## 3. Device & environment safety
- The user's personal phone is never a default test target. Iterate on the emulator;
  physical devices are for final verification only, with an explicit go-ahead for
  that session.
- Failures during testing must be classified before fixing: app bug vs. test-command
  bug vs. environment quirk. Say which one it is out loud, with evidence.

## 4. Command correctness across layers
- Commands crossing parsing layers (Git Bash → adb.exe → device shell → cmd) need
  device-side quoting; `%` must be doubled. On first use of a new command shape,
  verify the payload arrived intact (echo it on the target) before building
  conclusions on its output.
- On slow targets (software-rendered emulator): poll readiness signals (`pidof`,
  `dumpsys window`, UI dump) instead of fixed sleeps. A screenshot taken at an
  arbitrary time proves nothing about readiness.

## 5. Honest reporting
- Lead with the outcome — including failure. State what failed, the evidence, and
  the cause; never let a failure ride inside a success summary.
- When corrected by the user, acknowledge and change the behavior immediately —
  do not repeat the pattern the correction targeted.
