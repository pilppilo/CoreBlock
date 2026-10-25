package dev.coreblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the zero-shot decision math (D7): cosine similarity against
 * anchor embeddings and the margin -> verdict/confidence mapping. The threshold
 * contract under test: at the decision boundary confidence is 0.75 (below the
 * 0.80 cache-write gate and the 0.85 suppression gate), so only clear margins act.
 */
class EmbeddingDecisionTest {

    @Test
    fun `identical vectors have cosine similarity one`() {
        val v = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertEquals(1.0f, LiteRtEngine.cosineSimilarity(v, v), 1e-5f)
    }

    @Test
    fun `cosine is scale invariant`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)
        assertEquals(1.0f, LiteRtEngine.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun `opposite vectors have cosine similarity minus one`() {
        assertEquals(-1.0f, LiteRtEngine.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-0.5f, 0f)), 1e-5f)
    }

    @Test
    fun `strong promo margin yields promotional with saturated confidence`() {
        val (verdict, confidence) = LiteRtEngine.marginToVerdict(0.20f)
        assertEquals(Verdict.PROMOTIONAL, verdict)
        assertEquals(0.95f, confidence, 1e-5f)
    }

    @Test
    fun `strong personal margin yields pass`() {
        val (verdict, confidence) = LiteRtEngine.marginToVerdict(-0.20f)
        assertEquals(Verdict.PASS, verdict)
        assertEquals(0.95f, confidence, 1e-5f)
    }

    @Test
    fun `narrow margin stays ambiguous and never reaches the cache gate`() {
        val (verdict, confidence) = LiteRtEngine.marginToVerdict(0.03f)
        assertEquals(Verdict.AMBIGUOUS, verdict)
        assertTrue("ambiguous confidence ($confidence) must stay below the 0.80 cache gate", confidence < 0.80f)
    }

    @Test
    fun `decision margin is the exact boundary`() {
        assertEquals(Verdict.PROMOTIONAL, LiteRtEngine.marginToVerdict(LiteRtEngine.DECISION_MARGIN).first)
        assertEquals(Verdict.AMBIGUOUS, LiteRtEngine.marginToVerdict(LiteRtEngine.DECISION_MARGIN - 0.001f).first)
    }

    @Test
    fun `boundary confidence stays below the suppression gate`() {
        val conf = LiteRtEngine.marginToVerdict(LiteRtEngine.DECISION_MARGIN).second
        assertTrue("just-decided confidence ($conf) must be below the 0.85 cancel gate", conf < 0.85f)
    }
}
