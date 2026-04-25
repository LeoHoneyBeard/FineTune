package com.finetune.desktop.supporttriage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportTriageValidatorTest {
    @Test
    fun `parse final accepts valid schema`() {
        val parsed = SupportTriageValidator.parseFinal(
            """
            {
              "intent": "REFUND",
              "sentiment": "NEGATIVE",
              "urgency": "HIGH",
              "needs_human": true,
              "confidence": 0.91,
              "status": "OK"
            }
            """.trimIndent()
        )

        assertTrue(parsed.isValid)
        assertEquals(SupportIntent.REFUND, parsed.value?.intent)
        assertEquals(SupportStatus.OK, parsed.value?.status)
    }

    @Test
    fun `parse final rejects invalid enum`() {
        val parsed = SupportTriageValidator.parseFinal(
            """
            {
              "intent": "BILLING",
              "sentiment": "NEGATIVE",
              "urgency": "HIGH",
              "needs_human": true,
              "confidence": 0.91,
              "status": "OK"
            }
            """.trimIndent()
        )

        assertFalse(parsed.isValid)
    }

    @Test
    fun `parse final rejects confidence outside range`() {
        val parsed = SupportTriageValidator.parseFinal(
            """
            {
              "intent": "TECH",
              "sentiment": "NEUTRAL",
              "urgency": "MEDIUM",
              "needs_human": false,
              "confidence": 1.2,
              "status": "OK"
            }
            """.trimIndent()
        )

        assertFalse(parsed.isValid)
    }

    @Test
    fun `parse final normalizes compound intent`() {
        val parsed = SupportTriageValidator.parseFinal(
            """
            {
              "intent": "REFUND|TECH|INFO|OTHER",
              "sentiment": "NEGATIVE",
              "urgency": "HIGH",
              "needs_human": true,
              "confidence": 0.88,
              "status": "OK"
            }
            """.trimIndent()
        )

        assertTrue(parsed.isValid)
        assertEquals(SupportIntent.REFUND, parsed.value?.intent)
    }

    @Test
    fun `parse final rejects extra fields`() {
        val parsed = SupportTriageValidator.parseFinal(
            """
            {
              "intent": "TECH",
              "sentiment": "NEUTRAL",
              "urgency": "MEDIUM",
              "needs_human": false,
              "confidence": 0.8,
              "status": "OK",
              "extra": "not allowed"
            }
            """.trimIndent()
        )

        assertFalse(parsed.isValid)
    }

    @Test
    fun `parse stage one validates feature schema`() {
        val parsed = SupportTriageValidator.parseStage1(
            """
            {
              "has_payment_issue": true,
              "has_technical_issue": false,
              "has_info_request": false,
              "has_complaint": true,
              "mentions_access_problem": true,
              "mentions_refund": true,
              "emotion": "NEGATIVE",
              "noise_level": "LOW",
              "normalized_summary": "Проблема с оплатой и доступом."
            }
            """.trimIndent()
        )

        assertTrue(parsed.isValid)
        assertEquals(NoiseLevel.LOW, parsed.value?.noiseLevel)
    }

    @Test
    fun `parse compact stage one validates short schema`() {
        val parsed = SupportTriageValidator.parseStage1(
            "pay:1 tech:1 info:0 comp:1 access:1 refund:0 emo:NEG noise:LOW"
        )

        assertTrue(parsed.isValid)
        assertEquals(true, parsed.value?.hasPaymentIssue)
        assertEquals(SupportSentiment.NEGATIVE, parsed.value?.emotion)
        assertEquals("pay:1 tech:1 info:0 comp:1 access:1 refund:0 emo:NEG noise:LOW", parsed.normalizedJson)
    }

    @Test
    fun `parse compact stage two validates short schema`() {
        val parsed = SupportTriageValidator.parseStage2(
            "intent:REFUND urg:HIGH human:1 conf:0.92 status:OK"
        )

        assertTrue(parsed.isValid)
        assertEquals(SupportIntent.REFUND, parsed.value?.intent)
        assertEquals(SupportUrgency.HIGH, parsed.value?.urgency)
        assertEquals(true, parsed.value?.needsHuman)
    }

    @Test
    fun `parse compact stage two normalizes compound intent`() {
        val parsed = SupportTriageValidator.parseStage2(
            "intent:INFO|TECH urg:MED human:0 conf:0.81 status:OK"
        )

        assertTrue(parsed.isValid)
        assertEquals(SupportIntent.TECH, parsed.value?.intent)
    }

    @Test
    fun `parse compact stage one rejects invalid bit`() {
        val parsed = SupportTriageValidator.parseStage1(
            "pay:true tech:1 info:0 comp:1 access:1 refund:0 emo:NEG noise:LOW"
        )

        assertFalse(parsed.isValid)
    }

    @Test
    fun `parse compact stage two rejects extra text`() {
        val parsed = SupportTriageValidator.parseStage2(
            "intent:REFUND urg:HIGH human:1 conf:0.92 status:OK reason:payment"
        )

        assertFalse(parsed.isValid)
    }
}
