package dev.coreblock

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

// ============================================================================
// 1. Domain Models & Classifier Engine Abstraction
// ============================================================================

enum class Verdict {
    PASS,
    PROMOTIONAL,
    AMBIGUOUS
}

data class ClassificationInput(
    val sbnKey: String,
    val packageName: String,
    val title: String,
    val body: String,
    val channelId: String?,
    val category: String?,
    val isSecretVisibility: Boolean = false
)

data class ClassificationResult(
    val verdict: Verdict,
    val confidence: Float,
    val engineId: String,
    val latencyMs: Long,
    val modelVersion: String = "1.0",
    val reason: String = ""
)

interface ClassifierEngine {
    val id: String // "rules" | "litert" | "aicore"
    val supportsBackgroundExecution: Boolean
    /**
     * Prevents stub/unloaded models from writing heuristic guesses into the persistent
     * Room template cache (fixes F5c cache poisoning).
     */
    val isReadyForCachePersistence: Boolean
    suspend fun isAvailable(): Boolean
    suspend fun warmUp() {}
    suspend fun classify(input: ClassificationInput): ClassificationResult
}

// ============================================================================
// 2. Normalized Template Skeleton Hasher (Fixes F8: Greetings, URLs, Emojis, Numbers)
// ============================================================================

object NormalizedTemplateHasher {
    private val URL_REGEX = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val GREETING_REGEX = Regex(
        pattern = """^(hey|hi|hello|dear|welcome back)\s+[\p{L}\p{N}_-]{2,20}[,!:]?(?:\s+|$)""",
        option = RegexOption.IGNORE_CASE
    )
    private val DYNAMIC_TOKEN_REGEX = Regex(
        pattern = """\b(\d+[.,:\-/]?\d*%?|\$\d+(?:\.\d{2})?|\d+h\s*\d+m(?:\s*\d+s)?)\b""",
        option = RegexOption.IGNORE_CASE
    )
    private val EMOJI_AND_PUNCT_REGEX = Regex("""[\p{So}\p{Cn}\p{P}]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun computeHash(packageName: String, title: String, body: String): String {
        val skeleton = "$packageName|${normalize(title)}|${normalize(body)}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(skeleton.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun normalize(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(URL_REGEX, "<url>")
            .replace(GREETING_REGEX, "<greeting> ")
            .replace(DYNAMIC_TOKEN_REGEX, "<num>")
            .replace(EMOJI_AND_PUNCT_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
}

// ============================================================================
// 3. Room Persistence: Template Cache & 7-Day Rolling Decision Log (Fixes F5, F7, F11)
// ============================================================================

@Entity(tableName = "template_cache")
data class TemplateCacheEntity(
    @PrimaryKey val templateHash: String,
    val packageName: String,
    val normalizedSkeleton: String,
    val verdict: Verdict,
    val confidence: Float,
    val sourceEngineId: String,
    val userOverridden: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "decision_log")
data class DecisionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sbnKey: String,
    val packageName: String,
    val templateHash: String,
    val title: String,
    val body: String,
    val verdict: Verdict,
    val confidence: Float,
    val engineId: String,
    val latencyMs: Long,
    val modelVersion: String,
    val actionTaken: String, // "NONE" | "DRY_RUN" | "PENDING_CANCEL" | "CANCELLED"
    val userUndone: Boolean = false,
    val needsUserReview: Boolean = false,
    val aicoreAuditVerdict: Verdict? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface CoreBlockDao {
    @Query("SELECT * FROM template_cache WHERE templateHash = :hash LIMIT 1")
    suspend fun getTemplate(hash: String): TemplateCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(entity: TemplateCacheEntity)

    @Query("DELETE FROM template_cache WHERE templateHash = :hash AND userOverridden = 0")
    suspend fun evictNonOverriddenTemplate(hash: String)

    @Insert
    suspend fun insertDecisionLog(entry: DecisionLogEntity): Long

    @Query("UPDATE decision_log SET actionTaken = :actionTaken WHERE id = :decisionId")
    suspend fun updateActionTaken(decisionId: Long, actionTaken: String)

    @Query("SELECT * FROM decision_log ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getRecentDecisions(limit: Int = 50): List<DecisionLogEntity>

    @Query(
        """
        SELECT * FROM decision_log 
        WHERE actionTaken = 'PENDING_CANCEL' 
          AND timestampEpochMs < :cutoffEpochMs
        """
    )
    suspend fun getStalePendingCancels(cutoffEpochMs: Long): List<DecisionLogEntity>

    @Query(
        """
        SELECT * FROM decision_log 
        WHERE aicoreAuditVerdict IS NULL 
          AND engineId = 'litert' 
          AND confidence BETWEEN 0.65 AND 0.90 
        ORDER BY timestampEpochMs DESC 
        LIMIT :limit
        """
    )
    suspend fun getBorderlineDecisionsForAudit(limit: Int = 10): List<DecisionLogEntity>

    @Query(
        """
        UPDATE decision_log 
        SET aicoreAuditVerdict = :auditVerdict,
            needsUserReview = :needsReview
        WHERE id = :decisionId
        """
    )
    suspend fun updateAuditOutcome(
        decisionId: Long,
        auditVerdict: Verdict,
        needsReview: Boolean
    )

    @Query("UPDATE decision_log SET userUndone = 1, needsUserReview = 0 WHERE id = :decisionId")
    suspend fun markUndone(decisionId: Long)

    @Query(
        """
        SELECT * FROM decision_log 
        WHERE verdict = 'PROMOTIONAL' 
          AND userUndone = 0 
          AND timestampEpochMs >= :sinceEpochMs
        ORDER BY timestampEpochMs DESC
        """
    )
    suspend fun getSuppressedPromosSince(sinceEpochMs: Long): List<DecisionLogEntity>

    // F11 Privacy fix: Purge notification logs older than 7 days & unpinned templates older than 30 days
    @Query("DELETE FROM decision_log WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun purgeOldDecisionLogs(cutoffEpochMs: Long)

    @Query("DELETE FROM template_cache WHERE userOverridden = 0 AND updatedAtEpochMs < :cutoffEpochMs")
    suspend fun purgeStaleTemplates(cutoffEpochMs: Long)
}

@Database(
    entities = [TemplateCacheEntity::class, DecisionLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CoreBlockDatabase : RoomDatabase() {
    abstract fun dao(): CoreBlockDao

    companion object {
        @Volatile
        private var INSTANCE: CoreBlockDatabase? = null

        fun getInstance(context: Context): CoreBlockDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoreBlockDatabase::class.java,
                    "coreblock.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

// ============================================================================
// 4. Stage 1 & 3: Deterministic Rules Engine (OTP Safety Rails + Fast Promos)
// ============================================================================

class RulesEngine(
    private val allowlistedPackages: Set<String> = DEFAULT_ALLOWLIST
) : ClassifierEngine {

    override val id: String = "rules"
    override val supportsBackgroundExecution: Boolean = true
    override val isReadyForCachePersistence: Boolean = true

    private val otpSafetyRegex = Regex(
        pattern = """\b(verification|security code|passcode|otp|two-factor|2fa|login code|one-time|auth code|\d{4,8}\s+is your)\b""",
        option = RegexOption.IGNORE_CASE
    )

    private val promoKeywordRegex = Regex(
        pattern = """\b(\d{1,2}%\s*off|flash sale|limited[- ]time offer|promo code|use code|free shipping|bogo|deal ends|last chance to save|sponsored)\b""",
        option = RegexOption.IGNORE_CASE
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun classify(input: ClassificationInput): ClassificationResult {
        var result: ClassificationResult? = null
        val elapsed = measureTimeMillis {
            val combinedText = "${input.title} ${input.body}"

            // Stage 1: Hard safety rails (Allowlist, secret visibility, or OTP pattern)
            if (input.packageName in allowlistedPackages ||
                input.isSecretVisibility ||
                input.category == Notification.CATEGORY_CALL ||
                input.category == Notification.CATEGORY_ALARM ||
                input.category == Notification.CATEGORY_MESSAGE ||
                otpSafetyRegex.containsMatchIn(combinedText)
            ) {
                result = ClassificationResult(
                    verdict = Verdict.PASS,
                    confidence = 1.0f,
                    engineId = id,
                    latencyMs = 0L,
                    reason = "Hard safety rail (allowlist / OTP / message category)"
                )
                return@measureTimeMillis
            }

            // Stage 3a: Explicit OS promo category or channel hints
            if (input.category == Notification.CATEGORY_PROMO ||
                input.channelId?.contains("promo", ignoreCase = true) == true ||
                input.channelId?.contains("marketing", ignoreCase = true) == true ||
                input.channelId?.contains("offers", ignoreCase = true) == true
            ) {
                result = ClassificationResult(
                    verdict = Verdict.PROMOTIONAL,
                    confidence = 0.95f,
                    engineId = id,
                    latencyMs = 0L,
                    reason = "Notification channel/category marked promotional"
                )
                return@measureTimeMillis
            }

            // Stage 3b: Fast deterministic promotional keywords
            if (promoKeywordRegex.containsMatchIn(combinedText)) {
                result = ClassificationResult(
                    verdict = Verdict.PROMOTIONAL,
                    confidence = 0.90f,
                    engineId = id,
                    latencyMs = 0L,
                    reason = "Matched explicit promotional keyword rule"
                )
                return@measureTimeMillis
            }
        }

        return (result ?: ClassificationResult(
            verdict = Verdict.AMBIGUOUS,
            confidence = 0.50f,
            engineId = id,
            latencyMs = elapsed,
            reason = "No deterministic rule matched"
        )).copy(latencyMs = elapsed)
    }

    companion object {
        val DEFAULT_ALLOWLIST = setOf(
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.google.android.apps.authenticator2",
            "com.whatsapp",
            "org.thoughtcrime.securesms" // Signal
        )
    }
}

// ============================================================================
// 5. Stage 5: In-Process LiteRT Engine (Fixes F5c, F6, F12)
// ============================================================================

class LiteRtEngine(
    // F12 fix: LiteRT Interpreter is not thread-safe; confine all model calls to 1 thread.
    private val singleThreadDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) : ClassifierEngine {

    override val id: String = "litert"
    override val supportsBackgroundExecution: Boolean = true

    @Volatile
    private var modelLoaded: Boolean = false

    // F5c fix: Only persist to Room template_cache when a real model file is loaded,
    // never when running the fallback heuristic stub!
    override val isReadyForCachePersistence: Boolean
        get() = modelLoaded

    override suspend fun isAvailable(): Boolean = true

    override suspend fun warmUp() = withContext(singleThreadDispatcher) {
        // Production hook: Load EmbeddingGemma-2 / MobileBERT .tflite asset into LiteRT
        // Interpreter with GPU/NPU delegate on onListenerConnected() so the first notification
        // does not pay cold-start model loading latency (fixes F6).
        // Set modelLoaded = true once the real interpreter is initialized.
        modelLoaded = false
    }

    override suspend fun classify(input: ClassificationInput): ClassificationResult =
        withContext(singleThreadDispatcher) {
            var verdict = Verdict.PASS
            var confidence = 0.5f
            val latency = measureTimeMillis {
                val text = "${input.title} ${input.body}".lowercase()
                val promoSignals = listOf("sale", "discount", "offer", "reward", "points", "shop now", "deal")
                val hits = promoSignals.count { text.contains(it) }
                if (hits >= 2) {
                    verdict = Verdict.PROMOTIONAL
                    confidence = (0.78f + (hits * 0.06f)).coerceAtMost(0.94f)
                } else {
                    verdict = Verdict.PASS
                    confidence = 0.85f
                }
            }
            ClassificationResult(
                verdict = verdict,
                confidence = confidence,
                engineId = if (modelLoaded) id else "$id-stub",
                latencyMs = latency,
                modelVersion = if (modelLoaded) "embeddinggemma-2-int8" else "heuristic-stub-v1",
                reason = "Single-thread confined LiteRT classification"
            )
        }
}

// ============================================================================
// 6. Stage 6: Foreground-Only AICore Auditor (Fixes F5a Cache Poisoning)
// ============================================================================

class AicoreForegroundEngine : ClassifierEngine {
    override val id: String = "aicore"
    override val supportsBackgroundExecution: Boolean = false

    /**
     * D4: the audit loop stays dormant while classify() is a heuristic stub — otherwise a
     * constant placeholder verdict would flag innocent entries and evict real templates.
     * Flip to true in Phase 3 when the ML Kit GenAI Prompt API is actually wired.
     */
    @Volatile var realBackendWired: Boolean = false

    override val isReadyForCachePersistence: Boolean = false

    fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override suspend fun isAvailable(): Boolean = isAppInForeground()

    override suspend fun classify(input: ClassificationInput): ClassificationResult {
        if (!isAppInForeground()) {
            return ClassificationResult(
                verdict = Verdict.AMBIGUOUS,
                confidence = 0.0f,
                engineId = id,
                latencyMs = 0L,
                reason = "Skipped: AICore requires top foreground activity (BACKGROUND_USE_BLOCKED = 30)"
            )
        }

        // Production hook: Invoke ML Kit GenAI Prompt API on Gemini Nano.
        // Respect GenAiException.getRetryAfter() on PER_APP_BATTERY_USE_QUOTA_EXCEEDED.
        return ClassificationResult(
            verdict = Verdict.PROMOTIONAL,
            confidence = 0.92f,
            engineId = id,
            latencyMs = 310L,
            modelVersion = "gemini-nano-tensor-g6",
            reason = "Foreground AICore shadow audit"
        )
    }

    /**
     * F5a fix: If Gemini Nano disagrees with a LiteRT PROMOTIONAL verdict, do NOT write a
     * permanent PASS override (which would poison the cache on a single LLM hallucination).
     * Instead, evict the non-overridden template cache entry and flag the log item for user review.
     */
    suspend fun runForegroundShadowAudit(dao: CoreBlockDao) {
        if (!realBackendWired) return // D4: stub must not write audit outcomes
        if (!isAvailable()) return
        val candidates = dao.getBorderlineDecisionsForAudit(limit = 10)
        for (entry in candidates) {
            if (!isAppInForeground()) break
            val auditResult = classify(
                ClassificationInput(
                    sbnKey = entry.sbnKey,
                    packageName = entry.packageName,
                    title = entry.title,
                    body = entry.body,
                    channelId = null,
                    category = null
                )
            )
            if (auditResult.verdict != Verdict.AMBIGUOUS) {
                val disagreed = auditResult.verdict != entry.verdict
                dao.updateAuditOutcome(
                    decisionId = entry.id,
                    auditVerdict = auditResult.verdict,
                    needsReview = disagreed
                )
                if (disagreed) {
                    dao.evictNonOverriddenTemplate(entry.templateHash)
                }
            }
        }
    }
}

// ============================================================================
// 7. In-Memory Session Cache for PendingIntent Replay (Fixes F3, F4, F9)
// ============================================================================

object PendingIntentReplayCache {
    private val cache = LruCache<Long, PendingIntent>(64)

    fun put(decisionId: Long, intent: PendingIntent?) {
        if (intent != null) {
            synchronized(cache) { cache.put(decisionId, intent) }
        }
    }

    fun get(decisionId: Long): PendingIntent? =
        synchronized(cache) { cache.get(decisionId) }

    /**
     * Attempts to fire the original notification's PendingIntent if still valid in this session;
     * falls back cleanly to launching the target package's main activity (fixes F9).
     */
    fun replayOrLaunchApp(context: Context, decisionId: Long, packageName: String): Boolean {
        val pendingIntent = get(decisionId)
        if (pendingIntent != null) {
            try {
                pendingIntent.send()
                return true
            } catch (_: PendingIntent.CanceledException) {
                // Sender invalidated the PendingIntent; fall through to package launcher
            }
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launchIntent != null) {
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }
}

// ============================================================================
// 7b. Suppression Toggle (D5: dry-run by default — log only, cancel nothing)
// ============================================================================

object SuppressionSettings {
    private const val PREFS_NAME = "coreblock_suppression_prefs"
    private const val KEY_ENABLED = "suppression_enabled"

    /** False until the user flips the dashboard switch: every would-be cancel is logged as DRY_RUN. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

// ============================================================================
// 7c. Debug-only test hook (no-op unless FLAG_DEBUGGABLE): lets adb-driven
//     verification toggle suppression and fire Undo without Compose UI
//     automation, which is unreliable on the software-rendered emulator.
// ============================================================================

class SuppressionDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val dao = CoreBlockDatabase.getInstance(context.applicationContext).dao()
        when (intent.action) {
            ACTION_ENABLE_SUPPRESSION -> SuppressionSettings.setEnabled(context, true)
            ACTION_DISABLE_SUPPRESSION -> SuppressionSettings.setEnabled(context, false)
            ACTION_UNDO_LAST_CANCEL -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val target = dao.getRecentDecisions(limit = 50)
                            .firstOrNull { it.actionTaken == "CANCELLED" && !it.userUndone }
                        if (target != null) {
                            CoreBlockNotificationListener.undoAndReplay(context, dao, target)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_ENABLE_SUPPRESSION = "dev.coreblock.debug.ENABLE_SUPPRESSION"
        const val ACTION_DISABLE_SUPPRESSION = "dev.coreblock.debug.DISABLE_SUPPRESSION"
        const val ACTION_UNDO_LAST_CANCEL = "dev.coreblock.debug.UNDO_LAST_CANCEL"
    }
}

// ============================================================================
// 8. Module 1: Real-Time Notification Firewall Service (Fixes F1, F3, F4, F5b, F7, F12)
// ============================================================================

class CoreBlockNotificationListener : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    // F12 fix: Bound background processing concurrency
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default.limitedParallelism(4))
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()

    private lateinit var dao: CoreBlockDao
    private val rulesEngine = RulesEngine()
    private val liteRtEngine = LiteRtEngine()

    var promoConfidenceThreshold: Float = 0.85f

    override fun onCreate() {
        super.onCreate()
        dao = CoreBlockDatabase.getInstance(applicationContext).dao()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceScope.launch {
            // F6 fix: Warm up LiteRT interpreter on connection so first notification is fast
            liteRtEngine.warmUp()
            // F11 fix: Rolling 7-day privacy log retention & 30-day stale cache cleanup
            val now = System.currentTimeMillis()
            dao.purgeOldDecisionLogs(now - 7L * 24 * 3600 * 1000L)
            dao.purgeStaleTemplates(now - 30L * 24 * 3600 * 1000L)
            reconcileStalePendingCancels()
        }
    }

    /**
     * D2: a row stuck at PENDING_CANCEL means the process died between the write-ahead log
     * and cancelNotification. If the notification is still active the cancel never happened
     * (mark NONE); if it is gone the cancel did happen (mark CANCELLED). Only rows older
     * than 60 s are reconciled so in-flight processing is never raced.
     */
    private suspend fun reconcileStalePendingCancels() {
        val stale = dao.getStalePendingCancels(System.currentTimeMillis() - 60_000L)
        if (stale.isEmpty()) return
        val activeKeys = try {
            activeNotifications?.map { it.key }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            return // listener not fully connected; retry on next connect
        }
        for (entry in stale) {
            dao.updateActionTaken(
                decisionId = entry.id,
                actionTaken = if (entry.sbnKey in activeKeys) "NONE" else "CANCELLED"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing || !sbn.isClearable) return
        if (!inFlightKeys.add(sbn.key)) return // Deduplicate burst updates for the same key

        val notification = sbn.notification
        val isSecret = notification.visibility == Notification.VISIBILITY_SECRET
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        if (title.isBlank() && body.isBlank()) {
            inFlightKeys.remove(sbn.key)
            return
        }

        val contentIntent: PendingIntent? = notification.contentIntent
        val input = ClassificationInput(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            title = title,
            body = body,
            channelId = notification.channelId,
            category = notification.category,
            isSecretVisibility = isSecret
        )

        serviceScope.launch {
            try {
                processNotification(sbn.key, input, contentIntent)
            } finally {
                inFlightKeys.remove(sbn.key)
            }
        }
    }

    private suspend fun processNotification(
        sbnKey: String,
        input: ClassificationInput,
        contentIntent: PendingIntent?
    ) {
        val templateHash = NormalizedTemplateHasher.computeHash(
            packageName = input.packageName,
            title = input.title,
            body = input.body
        )

        // Stage 1: Hard safety rails (allowlist, OTP regex, secret/message categories)
        val ruleResult = rulesEngine.classify(input)
        if (ruleResult.verdict == Verdict.PASS && ruleResult.confidence == 1.0f) {
            return
        }

        // Stage 2: Check Room template cache for explicit User Overrides FIRST (Fixes F5b)
        val cached = dao.getTemplate(templateHash)
        if (cached != null && cached.userOverridden) {
            if (cached.verdict == Verdict.PASS) return
        }

        // Stage 3: Deterministic promo rules ("50% OFF", CATEGORY_PROMO) beat stale model cache (Fixes F5b)
        val finalResult: ClassificationResult = when {
            ruleResult.verdict == Verdict.PROMOTIONAL -> ruleResult

            // Stage 4: High-confidence non-overridden model template cache hit
            cached != null && cached.confidence >= promoConfidenceThreshold -> ClassificationResult(
                verdict = cached.verdict,
                confidence = cached.confidence,
                engineId = "cache:${cached.sourceEngineId}",
                latencyMs = 1L,
                reason = "Normalized template cache hit"
            )

            // Stage 5: Live LiteRT classification
            else -> {
                val liveResult = liteRtEngine.classify(input)
                // F5c fix: Only persist to template_cache when a real model is loaded
                if (liteRtEngine.isReadyForCachePersistence && liveResult.confidence >= 0.80f) {
                    dao.upsertTemplate(
                        TemplateCacheEntity(
                            templateHash = templateHash,
                            packageName = input.packageName,
                            normalizedSkeleton = NormalizedTemplateHasher.normalize("${input.title} ${input.body}"),
                            verdict = liveResult.verdict,
                            confidence = liveResult.confidence,
                            sourceEngineId = liveResult.engineId,
                            userOverridden = false
                        )
                    )
                }
                liveResult
            }
        }

        // D1: only deterministic rules, cache entries (written exclusively by real models),
        // or a loaded real model may suppress. The heuristic stub observes and logs only.
        val maySuppress = when {
            finalResult.engineId == "rules" -> true
            finalResult.engineId.startsWith("cache:") -> true
            else -> liteRtEngine.isReadyForCachePersistence
        }
        val wantsCancel = maySuppress &&
            finalResult.verdict == Verdict.PROMOTIONAL &&
            finalResult.confidence >= promoConfidenceThreshold
        // D5: dry-run by default — nothing is cancelled until the user enables suppression.
        val shouldCancel = wantsCancel && SuppressionSettings.isEnabled(this)

        // F7 fix: Write-ahead log BEFORE calling cancelNotification(sbnKey) so a crash
        // never leaves a cancelled notification without an Undo record.
        val decisionId = dao.insertDecisionLog(
            DecisionLogEntity(
                sbnKey = sbnKey,
                packageName = input.packageName,
                templateHash = templateHash,
                title = input.title,
                body = input.body,
                verdict = finalResult.verdict,
                confidence = finalResult.confidence,
                engineId = finalResult.engineId,
                latencyMs = finalResult.latencyMs,
                modelVersion = finalResult.modelVersion,
                actionTaken = when {
                    shouldCancel -> "PENDING_CANCEL"
                    wantsCancel -> "DRY_RUN"
                    else -> "NONE"
                }
            )
        )

        if (shouldCancel) {
            PendingIntentReplayCache.put(decisionId, contentIntent)
            // F3 & F4 fix: Always cancelNotification instead of snoozeNotification(24h)
            // to eliminate the 24-hour resurrection loop.
            cancelNotification(sbnKey)
            dao.updateActionTaken(decisionId, "CANCELLED")
        }
    }

    companion object {
        /**
         * One-tap Undo & Replay handler: permanently whitelists the normalized skeleton
         * in Room (userOverridden = true) and fires the cached PendingIntent (or opens the app).
         */
        suspend fun undoAndReplay(
            context: Context,
            dao: CoreBlockDao,
            decision: DecisionLogEntity
        ): Boolean {
            dao.markUndone(decision.id)
            dao.upsertTemplate(
                TemplateCacheEntity(
                    templateHash = decision.templateHash,
                    packageName = decision.packageName,
                    normalizedSkeleton = NormalizedTemplateHasher.normalize("${decision.title} ${decision.body}"),
                    verdict = Verdict.PASS,
                    confidence = 1.0f,
                    sourceEngineId = "user_undo",
                    userOverridden = true
                )
            )
            return PendingIntentReplayCache.replayOrLaunchApp(
                context = context,
                decisionId = decision.id,
                packageName = decision.packageName
            )
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return enabledListeners.contains(context.packageName)
        }
    }
}