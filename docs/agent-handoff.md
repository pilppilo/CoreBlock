# CoreBlock — Agent Handoff (2026-10-25, end of Windows session)

Read **AGENTS.md** first — its rules are binding and were expanded by events below.
This file supersedes any earlier handoff; it was written as the project moved from
the Windows/Hyper-V machine to a Linux PC.

## Project

CoreBlock: privacy-first, fully on-device notification filter for Android.
Master plan is `plan.md` (v3). Hybrid engine, now real: **LiteRT EmbeddingGemma 2**
for background classification (Phase 2 ✅), AICore/Gemini Nano planned as
foreground-only auditor (Phase 3, needs physical Pixel).

## State

Branch `glm`, 12 commits (aaf7287 → 08bc583), clean tree, no remote (this repo
arrived as a `git bundle`; origin points at the bundle file). Phases 0–2 ✅
**verified on emulator** with DB evidence:

- **Phase 1** ✅: cancel → WAL → undo → template-allowlist → override-immunity,
  plus user-editable per-app allowlist (screen + SharedPreferences + live
  provider into RulesEngine).
- **Phase 2** ✅ (08bc583): real EmbeddingGemma 2 text-270m zero-shot engine live
  in `LiteRtEngine`. Proof rows: "Your order has shipped…" → PASS 0.95 via
  `litert`; keyword-free "Upgrade to Premium today…" → PROMOTIONAL 0.827 via
  `litert` — semantic detection the regex layer cannot do. No training involved
  (D7: anchor cosine margins; only tuning surface = `DECISION_MARGIN` 0.05 and
  `STRONG_MARGIN` 0.12 in the LiteRtEngine companion).
- Toolchain (bumped this session, forced by litertlm metadata — see D7):
  **Kotlin 2.4.21, KSP 2.3.12, AGP 8.12.3, Room 2.8.5, Gradle 8.13,
  litertlm-android 0.18.0**. APK 39.6 MB, merged manifest requests zero
  permissions beyond AndroidX boilerplate (ML Kit and litert-gpu stripped until
  their phases — D6).
- Decisions D1–D7 are in `plan.md` §12. Dry-run default (D5) is intact on the
  emulator: nothing is cancelled until the dashboard switch is flipped.

## Linux migration notes (this machine's specifics that did NOT travel)

1. **Model file is gitignored**: `models/embeddinggemma-2-text-270m.litertlm`,
   exactly **164,626,432 bytes** from
   `huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm`.
   Copy it or re-download (verify byte count), then
   `adb push` to `/sdcard/Android/data/dev.coreblock/files/`. Without it the
   engine is a log-only heuristic stub **by design** (D1) — a missing model must
   never look like a bug.
2. `local.properties` — regenerate (SDK path) on Linux.
3. First `./gradlew assembleDebug` re-fetches the whole toolchain; needs JDK 17–21
   + Android SDK. Tests: `./gradlew testDebugUnitTest` (26/26 green at 08bc583).
4. The AVD/emulator is per-machine. Linux + USB is also the natural place to
   attach a physical Pixel (Phases 3–4 need one).

## The user — how to work with them

- Scope discipline is non-negotiable: do exactly what's asked, then stop.
- Downloads/installs: manifest → explicit approval → execute → log in
  `docs/environment-log.md`. No exceptions.
- **State phase transitions out loud before starting work.** This session the
  user was rightly upset when work moved past Phase 2 to other things after they
  had paused it; when a phase is blocked or skipped, say so in one sentence and
  get an explicit choice. Never bury a direction change inside a recommendation.
- Honest failure reporting: lead with the outcome, classify failures (app bug vs
  test-tooling vs environment) with evidence before fixing.
- If environment trouble eats >10 minutes of wall time, surface it and ask
  whether to keep pushing.
- Commits at approved milestones on `glm`, descriptive messages — authorized.

## Hard-won gotchas (all verified this session)

- **Never force-stop the app while its notification listener is granted.**
  Android's stopped state blocks rebinding even after `am start` or grant
  toggles; recovery may require a reboot. To check binding, dump
  `dumpsys notification --noredact` and look for the component line carrying
  `INotificationListener$Stub$Proxy`.
- **Listener binds can ANR during cold start** (ART class verification grind on
  slow machines); NMS usually recovers and connects ~1 min later. Poll readiness
  signals; never post test notifications before the proxy line exists.
- **Emulator intermittently delivers redacted projections to listeners**
  (title = app label, body "Sensitive notification content hidden") regardless
  of keyguard state — `locksettings set-disabled true` did not stop it. The app
  correctly PASSes redacted content; retry later if a test needs full text.
- `cmd notification post` posts as package `com.android.shell`. In the
  Git-Bash→adb.exe shape, single `%` arrives intact (no doubling). On Linux
  shells, re-verify payload fidelity on first use (AGENTS.md §4).
- Room writes land in the WAL first: pull `coreblock.db` + `-wal` + `-shm`
  together and read with python sqlite3.
- **Incremental APK packaging can leave orphaned entries** — after dependency
  changes, delete the APK and rebuild before measuring size (we saw ~8 MB of
  dead bytes this way).
- Debug hooks (FLAG_DEBUGGABLE-guarded `SuppressionDebugReceiver`):
  ENABLE/DISABLE_SUPPRESSION, UNDO_LAST_CANCEL, ALLOWLIST_ADD/REMOVE/RESET
  (extra `package_name`) — drive real production functions via adb broadcast.
- Gates worth remembering: cache-write gate 0.80, cache-USE gate 0.85,
  suppression gate 0.85, boundary confidence 0.75 — a 0.83 promo verdict is
  logged and cached but will not cancel and will not short-circuit a repeat.
- Model latency: ~3–4 s per embedding on a software-rendered emulator; ~150 ms
  CPU / ~42 ms NPU on real Tensor hardware (Pixel 10 measurements). The <15 ms
  plan target is a Tensor-G6-on-device number, not an emulator number.

## Suggested next steps

1. Tune `DECISION_MARGIN`/`STRONG_MARGIN` (and maybe anchor sets) against real
   notification traffic — the D7 constants are the whole tuning surface.
2. Phase 5 (read-only accessibility feed detection) was drafted but never
   implemented — treat as NOT approved; re-propose if the user asks.
3. Phase 3 hardware decision (dedicated dev Pixel 9+ vs deferring Phases 3–4)
   remains open with the user. Their personal phone is never a test target.
4. Phase 2 distribution question (bundling/Play Asset Delivery vs adb push) is
   unresolved — current delivery is adb-push to external files dir.
