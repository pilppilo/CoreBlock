package dev.coreblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the normalized skeleton hasher — the component the >80% cache
 * hit-rate metric (plan.md §10) rests on.
 */
class NormalizedTemplateHasherTest {

    private fun hash(title: String, body: String, pkg: String = "com.example.shop") =
        NormalizedTemplateHasher.computeHash(pkg, title, body)

    @Test
    fun `dynamic numbers prices and timers collapse to the same skeleton`() {
        val a = hash("Flash sale", "25% off ends in 03:14:09! Was $49.99")
        val b = hash("Flash sale", "40% off ends in 09:59:59! Was $19.99")
        assertEquals(a, b)
    }

    @Test
    fun `personalized greetings normalize away`() {
        val a = hash("Hey Alex,", "your favorites are 25% off")
        val b = hash("Hi Sam!", "your favorites are 25% off")
        assertEquals(a, b)
    }

    @Test
    fun `greeting followed by body text also normalizes`() {
        val a = hash("Newsletter", "Hey Alex, your points expire soon — 500 pts left")
        val b = hash("Newsletter", "Hey Jordan, your points expire soon — 800 pts left")
        assertEquals(a, b)
    }

    @Test
    fun `urls normalize to a placeholder`() {
        val a = hash("New drops", "Shop now https://shop.example.com/spring?u=alex")
        val b = hash("New drops", "Shop now www.example.com/other")
        assertEquals(a, b)
    }

    @Test
    fun `emoji and punctuation do not affect the skeleton`() {
        val a = hash("FIRE SALE", "50% off everything today")
        val b = hash("🔥 FIRE 🔥 SALE 🔥", "50% off everything — today!!!")
        assertEquals(a, b)
    }

    @Test
    fun `different content yields different hashes`() {
        assertNotEquals(
            hash("Your order shipped", "Arriving Tuesday"),
            hash("Flash sale", "50% off today")
        )
    }

    @Test
    fun `package name participates in the hash`() {
        val a = NormalizedTemplateHasher.computeHash("com.a.app", "Title", "Body")
        val b = NormalizedTemplateHasher.computeHash("com.b.app", "Title", "Body")
        assertNotEquals(a, b)
    }

    @Test
    fun `hash is 32 lowercase hex characters`() {
        val h = hash("Title", "Body")
        assertEquals(32, h.length)
        assertTrue(h.matches(Regex("[0-9a-f]{32}")))
    }
}
