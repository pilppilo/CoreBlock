# CoreBlock — Project Plan (v3: Post-Audit Hardened Architecture)

An AI-driven, privacy-first notification & content filter for Android 11+ (`minSdk = 30`), optimized for the **[Pixel 11 Pro](https://hiwavemakers.com/blog/tensor-g6-gemini-nano-on-device-ai-pixel-11-kids/)** ([Tensor G6](https://www.androidauthority.com/pixel-11-tensor-g6-tpu-upgrade-explained-3696999/)). Runs entirely on-device using a **hybrid two-tier AI engine**: **LiteRT** ([EmbeddingGemma 2](https://developers.google.com/edge/litert-lm/embedding_models) / MobileBERT INT8) for real-time background filtering and **AICore** (Gemini Nano via [ML Kit GenAI APIs](https://developers.google.com/ml-kit/genai)) for foreground auditing, template review, and daily digests. No VPN, no DNS lists, no network calls, no cloud AI.

**Working thesis:** Traditional ad blockers match URL lists and lose to native ads. CoreBlock matches *meaning* — on-device models classify content the way a human reader would, which is un-bypassable by domain rotation.

**Key architectural insight:** [ML Kit GenAI APIs](https://developers.google.com/ml-kit/genai) enforce a strict foreground-only execution policy ([`GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED = 30`](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode)). Because `NotificationListenerService` and `AccessibilityService` run while the user is in other apps or on the lock screen, **LiteRT handles live background classification (~5–15 ms model inference on Tensor G6, warmed in `onListenerConnected()`)**, while **AICore (Gemini Nano) runs when CoreBlock is in the foreground** to audit uncertain classifications, flag potential false positives for review, and generate the daily digest.

## 1. Strategy

| Decision | Choice | Rationale |
|---|---|---|
| First module | Notification firewall (`cancelNotification`) | Cheapest API surface, Play-viable, near-zero battery, avoids `snoozeNotification` daily resurrection loops |
| Real-time background AI engine | In-process LiteRT ([EmbeddingGemma 2](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/) / MobileBERT INT8) | Runs inside `NotificationListenerService` and `AccessibilityService` with zero `BACKGROUND_USE_BLOCKED` errors or AICore quota throttling; confined to a single-thread dispatcher (`limitedParallelism(1)`) for interpreter thread safety |
| Foreground audit & digest engine | AICore via [ML Kit GenAI](https://developers.google.com/ml-kit/genai) ([Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started) + [Summarization API](https://developers.google.com/ml-kit/genai/summarization/android)) | 0 MB bundled footprint, shared system memory, used when CoreBlock UI opens to audit low-margin decisions and render daily summaries |
| Model delivery | Bundled/on-demand LiteRT via Play Asset Delivery + system AICore | Works across all Android 11+ (`minSdk = 30`) devices via LiteRT while unlocking Gemini Nano auditing and summarization on supported flagships ([Pixel 9/10/11 series, Galaxy S26 series](https://developers.google.com/ml-kit/genai)) |
| Proof method | In-app benchmark & shadow-audit dashboard | Logs real-time LiteRT decisions and compares them against foreground AICore shadow evaluations and user undo feedback |
| Feed filtering | Read-only detection first, removal later | Detection is the solved half; removal is fragile (see Module 2 constraints) |

## 2. Architecture

### 2.1 Tiered decision pipeline (cheapest & highest-trust check first)

The neural model is the tie-breaker, not the gatekeeper. Deterministic safety rails and user overrides always take precedence over cached model guesses.

```
notification/content arrives (Background Service)
        │
        ▼
[1] Hard Safety Rails ──── allowlisted apps, OTP regex, VISIBILITY_SECRET → PASS (never touched)
        │
        ▼
[2] User Override Cache ── explicit user Undo/Allow template override in Room → PASS
        │
        ▼
[3] Fast Deterministic ─── "50% OFF", "Flash sale", `CATEGORY_PROMO` → PROMOTIONAL (<1 ms)
        │ ambiguous
        ▼
[4] Model Template Cache ─ (package + normalized skeleton hash, high-conf, non-expired) → cached verdict
        │ cache miss
        ▼
[5] LiteRT Engine ──────── EmbeddingGemma 2 / MobileBERT (single-thread NPU/GPU) → write-ahead log → act
        │
        ▼ (Asynchronous — when user opens CoreBlock in Foreground)
[6] AICore Auditor ─────── Gemini Nano (Prompt API) audits borderline LiteRT verdicts → evicts/flags disagreements
```

### 2.2 Normalized template hashing

Promotional notifications frequently inject personalized greetings, URLs, emojis, and dynamic numbers (`"Hey Alex, 25% off ends in 03:14:09! https://shop.example/x"`). Before querying or writing to the Room template cache, `NormalizedTemplateHasher` normalizes the text skeleton:
1. Lowercase and replace URLs (`https?://\S+`) with `<url>`.
2. Strip leading personalized greetings (`"hey alex,"`, `"hi sam!"`) into `<greeting>`.
3. Replace digit sequences, prices, percentages, and countdown timers with `<num>`.
4. Strip emojis/pictographs (`\p{So}`) and punctuation, then collapse whitespace.
5. Hash `packageName + "|" + normalizedTitle + "|" + normalizedBody` via SHA-256.

### 2.3 Engine abstraction

```kotlin
interface ClassifierEngine {
    val id: String                          // "rules" | "litert" | "aicore"
    val supportsBackgroundExecution: Boolean
    val isReadyForCachePersistence: Boolean // false for stub/unloaded models to prevent cache poisoning
    suspend fun isAvailable(): Boolean
    suspend fun warmUp() {}
    suspend fun classify(input: ClassificationInput): ClassificationResult
}
```

## 3. Module 1 — Semantic Notification Firewall (P0)

The entry point. Android natively supports silent cancellation via `NotificationListenerService` (declared with `android:exported="true"` and `BIND_NOTIFICATION_LISTENER_SERVICE` so the OS system server can bind).

* **Warm-up (`onListenerConnected`)**: Pre-loads the `LiteRtEngine` interpreter on a single-threaded dispatcher (`Dispatchers.Default.limitedParallelism(1)`) and runs the 7-day rolling privacy log purge so the first notification does not pay cold model-init latency.
* **Capture (`onNotificationPosted`)**: Deduplicates in-flight keys via a concurrent set; extracts `packageName`, `Notification.EXTRA_TITLE`, `Notification.EXTRA_TEXT`, `Notification.EXTRA_BIG_TEXT`, channel ID, and `contentIntent` (`PendingIntent`).
* **Android 15/16+ Sensitive Notification Handling**: Starting in [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all) and [Android 16](https://www.androidauthority.com/android-16-sensitive-notifications-lock-screen-3501564/), the OS hides or redacts sensitive OTP notification content from untrusted listeners before `onNotificationPosted` runs. CoreBlock pairs its OTP regex with a default allowlist for messaging, authenticator, and banking packages plus `VISIBILITY_SECRET` checks.
* **Write-Ahead Logging & Suppression (`cancelNotification`)**:
  * Why `cancelNotification(sbn.key)` instead of `snoozeNotification`: Android's `NotificationListenerService` has no public `unsnooze` API, and snoozing a promo for 24 hours causes the OS to re-post it (with heads-up and sound) 24 hours later in an endless daily resurrection loop.
  * Before calling `cancelNotification(sbn.key)`, CoreBlock writes the `DecisionLogEntity` to Room (write-ahead) and stores the notification's `contentIntent` (`PendingIntent`) in a bounded in-memory `LruCache<Long, PendingIntent>` (since kernel `PendingIntent` tokens cannot be persisted in SQLite across reboots).
  * Note on HUD banner flash: Cached templates and deterministic rules cancel in <5 ms (no visible flash), while first-seen templates that fall through to `LiteRtEngine` (~9 ms inference + Room/binder overhead) may briefly flash the heads-up banner once before learning the template.
* **One-Tap Undo & Deep-Link Replay**:
  * Tapping **Undo / Open** in the CoreBlock log marks `userUndone = true`, writes a permanent `userOverridden = true` `PASS` entry to `template_cache` (so future notifications matching that skeleton are never blocked), and invokes the cached `PendingIntent.send()` (falling back to `PackageManager.getLaunchIntentForPackage(pkg)` if the source app invalidated the `PendingIntent` or the device rebooted).
* **Privacy & Data Retention**:
  * `decision_log` automatically purges records older than 7 days (`RETENTION_DAYS = 7`) so notification text is never retained indefinitely on disk.

## 4. Module 2 — Feed Post Filter via Accessibility (P2, read-only first)

### 4.1 Detection (the solved half)
* `AccessibilityService` listening to `TYPE_WINDOW_CONTENT_CHANGED` + `TYPE_VIEW_SCROLLED`.
* Debounce aggressively (400–600 ms) and scope strictly to user-selected social/feed packages.
* **Scan-when-idle:** Trigger node tree extraction (`rootInActiveWindow`) only after scrolling stops for 500 ms. Run extracted post containers through the same pipeline (`RulesEngine` → `LiteRtEngine`).

### 4.2 Image-baked ads (Multimodal pipeline)
1. Accessibility heuristics flag a suspicious image-heavy post container.
2. `AccessibilityService.takeScreenshot()` (rate-limited by Android to ~1/sec — well-suited for idle scanning).
3. Crop container bounds and classify in-process using multimodal **[EmbeddingGemma 2 on LiteRT](https://developers.google.com/edge/litert-lm/embedding_models)** (740M parameters, unified 768-dim multimodal embedding space).
4. Optional foreground inspection: Flagged ad crops can be reviewed inside the CoreBlock app using AICore's multimodal **[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)** or [Image Description API](https://developers.google.com/ml-kit/genai/image-description/android).

### 4.3 Removal (Phase 6 experimental only)
* Phase 5 ships **detection-only** ("CoreBlock flagged 12 sponsored posts in Reddit today"). Removal is Phase 6, experimental, per-app opt-in, conservative rate limits.

## 5. Module 3 — Daily Digest & Foreground AICore Auditor (P1.5)

When the user opens CoreBlock in the foreground (`Lifecycle.State.RESUMED`), `AicoreForegroundEngine` runs two jobs:
1. **One-Line Daily Digest ([ML Kit Summarization API](https://developers.google.com/ml-kit/genai/summarization/android)):** Summarizes suppressed promotional notifications from the last 24 hours.
2. **Non-Destructive Shadow Audit ([ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)):** Re-evaluates borderline `LiteRtEngine` classifications (`confidence` between `0.65` and `0.90`).
   * **Anti-poisoning policy:** A single Gemini Nano disagreement does **not** write a permanent `userOverridden` `PASS` rule. Instead, if Gemini Nano disagrees with a `PROMOTIONAL` verdict from LiteRT, CoreBlock evicts the model's `template_cache` entry (forcing fresh evaluation next time) and surfaces the item in the UI with a **"Needs Review — Did we block a real alert?"** badge so only the user can lock a permanent override.

## 6. AICore & Pixel 11 Pro Operational Boundaries

* **Foreground-only constraint:** AICore throws [`GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED` (`30`)](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode) unless CoreBlock is the top foreground activity. Check `ProcessLifecycleOwner` (`Lifecycle.State.RESUMED`) before every AICore call.
* **Per-app battery quota:** AICore enforces a rolling per-UID quota and throws [`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode) on bursty requests. Cap foreground audits to 10 items per launch and respect `GenAiException.getRetryAfter()`.
* **Locked bootloader requirement:** The [ML Kit GenAI documentation](https://developers.google.com/ml-kit/genai/prompt/android/get-started) disables AICore on devices with an unlocked bootloader.
* **Supported devices:** [ML Kit GenAI](https://developers.google.com/ml-kit/genai) supports the Pixel 9, Pixel 10, and [Pixel 11 series](https://hiwavemakers.com/blog/tensor-g6-gemini-nano-on-device-ai-pixel-11-kids/) (plus Galaxy S26 series and select OEM flagships; Galaxy S25 is excluded from the [Prompt API](https://www.isyncevolution.com/blog/flutter-on-device-ai-offline-vs-cloud)).

## 7. Benchmark Dashboard (the proof)

Every decision logs: engine ID, latency, confidence, verdict, foreground AICore shadow agreement, user undo ground truth, and model version (`getBaseModelName()`).

```
LiteRT (Live BG)   87 decisions   median   9 ms   1 undo (FP)
Rules  (Live BG)  143 decisions   median  <1 ms   0 undos (FP)
AICore (FG Audit)  24 audited     median 310 ms   flagged 1 borderline item
Template cache hit rate: 84%      Battery overhead: <0.8%/day
```

## 8. Build Phases

| Phase | Deliverable | Exit Criteria |
|---|---|---|
| 0 — Skeleton | Buildable Kotlin 2.x + Compose app (`MainActivity`), `NotificationListenerService` (`exported="true"`), permission UX | App compiles cleanly and displays live notification stream on Pixel 11 Pro |
| 1 — Rules & Cancel MVP | Allowlist, normalized template cache (Room), keyword rules, write-ahead log + `cancelNotification` + Undo/Open replay | Obvious promos cancelled reliably; Undo whitelists template & replays deep link; 7-day log retention active |
| 2 — LiteRT Live Engine | Single-thread `LiteRtEngine` ([EmbeddingGemma 2](https://developers.google.com/edge/litert-lm/embedding_models) / MobileBERT) warmed in `onListenerConnected()`, guarded cache write-through | Live background AI classification running in <15 ms inference on Tensor G6 |
| 3 — AICore Foreground Auditor & Benchmark | `AicoreForegroundEngine` ([Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)) shadow audit on app open, cache eviction on disagreement, Benchmark UI | Head-to-head LiteRT vs. AICore agreement & latency metrics visible in-app |
| 4 — Daily Digest | [Summarization API](https://developers.google.com/ml-kit/genai/summarization/android) daily digest card generated on foreground open | One-line digest of suppressed promos renders cleanly with quota fallback |
| 5 — Accessibility (Read-Only) | Event debouncing, scan-when-idle node extraction, per-app opt-in, detection-only feed report | "Flagged N sponsored posts" report works; zero UI jank |
| 6 — Multimodal & Feed Cleanup (Experimental) | `takeScreenshot()` + [EmbeddingGemma 2](https://developers.google.com/edge/litert-lm/embedding_models) vision pipeline; optional idle overlay | Image-baked ads detected on idle scroll; sideload distribution flow verified |

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| False-positive cancellation of OTP / security / banking alerts | Severe (missed codes) | Default banking/2FA allowlist, OTP regex, write-ahead log, in-memory `PendingIntent` replay + app-launch fallback, one-tap permanent template allowlist |
| AICore throws `BACKGROUND_USE_BLOCKED` (ErrorCode 30) | Crash or silent failure if called from services | Use `LiteRtEngine` for all background service inference; gate `AicoreForegroundEngine` behind `Lifecycle.State.RESUMED` |
| Template cache poisoning from stub or single LLM hallucination | Permanent mis-filtering of a template | Block cache writes when `isReadyForCachePersistence == false`; put deterministic rules ahead of model cache; AICore disagreement evicts model cache rather than locking `userOverridden` |
| First-seen notification HUD banner flash | Brief visual flash before cancellation | Warm up `LiteRtEngine` in `onListenerConnected()`; subsequent notifications hit normalized template cache (<5 ms) |
| LiteRT interpreter concurrency crash | Native crash on burst notifications | Confine `LiteRtEngine` to `Dispatchers.Default.limitedParallelism(1)` and deduplicate in-flight `sbn.key` values |
| Unbounded notification text storage on disk | Privacy risk | Automatic 7-day rolling TTL purge on `decision_log` and 30-day TTL on non-overridden `template_cache` entries |

## 10. Success Metrics

* **Precision on personal/transactional: ≥ 99.5%** — never suppress an OTP or personal message.
* **Promo recall: ≥ 90%** after one week of template learning and foreground AICore auditing.
* **Median background decision latency:** cached templates & rules < 5 ms (no HUD flash); first-seen `LiteRtEngine` inference ≤ 15 ms on Pixel 11 Pro (single brief HUD flash possible on first sight).
* **Template cache hit rate:** > 80% after one week of full greeting/URL/emoji/number skeleton normalization.
* **Battery overhead:** < 1%/day.
* **Reversibility:** Every cancelled notification logged write-ahead with one-tap template allowlisting and `PendingIntent` / app-launch replay.

## 11. Tech Stack

* **Language & UI:** Kotlin 2.1.0, Jetpack Compose (Material 3, `org.jetbrains.kotlin.plugin.compose`)
* **Real-time Background AI:** LiteRT with [EmbeddingGemma 2](https://developers.google.com/edge/litert-lm/embedding_models) or MobileBERT INT8 (single-thread confined NPU/GPU execution on Tensor G6)
* **Foreground Audit & Summarization AI:** [ML Kit GenAI APIs](https://developers.google.com/ml-kit/genai) (`com.google.mlkit:genai-prompt`, `com.google.mlkit:genai-summarization`, `com.google.mlkit:genai-image-description`) on AICore
* **Persistence:** Room (7-day rolling decision log, normalized template cache), Jetpack DataStore (preferences), in-memory `LruCache<Long, PendingIntent>` for session deep-link replay
* **Target SDK:** `minSdk = 30` (Android 11+), `targetSdk = 36`

## 12. Open Decision Log

| # | Question | Resolution (2026-10-24) |
|---|---|---|
| D1 | May the heuristic LiteRT stub cancel notifications before the real model loads? | **No.** Stub verdicts are logged only. Suppression requires a `rules` verdict, a `cache:*` hit (only real models write those), or `isReadyForCachePersistence == true`. |
| D2 | How are rows stuck at `PENDING_CANCEL` handled? | Reconciled on `onListenerConnected` for rows older than 60 s: notification still active → `NONE` (cancel never happened); gone → `CANCELLED`. |
| D3 | `CATEGORY_MESSAGE` auto-pass exempts messaging-app marketing — accept? | **Accepted** as a deliberate precision-over-recall trade-off (never eat a person's message). Revisit with per-app controls in Phase 1 UX. |
| D4 | May the stub AICore auditor write audit outcomes? | **No.** `runForegroundShadowAudit` stays dormant (`realBackendWired = false`) until the real ML Kit Prompt API is wired in Phase 3. |
| D5 | Should the app cancel notifications out of the box? | **No — dry-run by default.** Suppression is disabled until the user flips the dashboard switch (`SuppressionSettings`); until then every would-be cancel is logged as `DRY_RUN` and no notification is touched. |
| D6 | Keep the ML Kit genai dependencies before Phase 3? | **No — stripped (2026-10-25).** `genai-prompt`/`genai-summarization`/`genai-image-description` were unused (AICore auditor is a dormant stub, D4) and injected `INTERNET`, network/storage permissions; ML Kit GenAI is a thin AICore client so APK size was unaffected — the real weight is LiteRT native libs (25.6 MB, 4 ABIs). Same day: `litert-gpu` also stripped after the manifest-merger report traced the surviving `READ_PHONE_STATE`/storage permissions to its legacy manifest (`org.tensorflow.lite.gpu.api` targetSdk < 4) — saves ~11 MB too. Core `litert` stays for Phase 2; each dep returns with its phase. Phase 2 model download also held off by the user the same day — not re-proposed unless asked. |
