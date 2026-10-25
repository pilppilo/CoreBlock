package dev.coreblock

import android.app.Notification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the deterministic safety rails and fast promo rules — the
 * components the ≥99.5% precision-on-personal metric (plan.md §10) rests on.
 */
class RulesEngineTest {

    private val engine = RulesEngine(allowlistedPackages = setOf("com.bank.trusted"))

    private fun input(
        pkg: String = "com.example.shop",
        title: String = "",
        body: String = "",
        category: String? = null,
        channelId: String? = null,
        secret: Boolean = false
    ) = ClassificationInput(
        sbnKey = "key",
        packageName = pkg,
        title = title,
        body = body,
        channelId = channelId,
        category = category,
        isSecretVisibility = secret
    )

    @Test
    fun `otp shaped bodies always pass`() = runTest {
        val r = engine.classify(input(title = "Verification", body = "Your verification code is 482913"))
        assertEquals(Verdict.PASS, r.verdict)
        assertEquals(1.0f, r.confidence, 0.0f)
    }

    @Test
    fun `allowlisted packages always pass`() = runTest {
        val r = engine.classify(input(pkg = "com.bank.trusted", body = "50% off loans!"))
        assertEquals(Verdict.PASS, r.verdict)
    }

    @Test
    fun `secret visibility always passes`() = runTest {
        val r = engine.classify(input(body = "Flash sale", secret = true))
        assertEquals(Verdict.PASS, r.verdict)
    }

    @Test
    fun `otp safety beats promo keywords`() = runTest {
        val r = engine.classify(
            input(title = "50% off security keys", body = "Use code X — one-time code 4482")
        )
        assertEquals(Verdict.PASS, r.verdict)
    }

    @Test
    fun `message call and alarm categories always pass`() = runTest {
        val categories = listOf(
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM
        )
        for (cat in categories) {
            assertEquals(Verdict.PASS, engine.classify(input(body = "50% off", category = cat)).verdict)
        }
    }

    @Test
    fun `promo category is promotional`() = runTest {
        val r = engine.classify(input(body = "Weekly digest", category = Notification.CATEGORY_PROMO))
        assertEquals(Verdict.PROMOTIONAL, r.verdict)
    }

    @Test
    fun `promo channel id is promotional`() = runTest {
        val r = engine.classify(input(body = "Weekly digest", channelId = "offers_and_promos"))
        assertEquals(Verdict.PROMOTIONAL, r.verdict)
    }

    @Test
    fun `explicit promo keyword is promotional`() = runTest {
        val r = engine.classify(input(title = "Flash sale", body = "Everything must go"))
        assertEquals(Verdict.PROMOTIONAL, r.verdict)
    }

    @Test
    fun `neutral content is ambiguous`() = runTest {
        val r = engine.classify(input(title = "Lunch?", body = "Same place as last time"))
        assertEquals(Verdict.AMBIGUOUS, r.verdict)
    }
}
