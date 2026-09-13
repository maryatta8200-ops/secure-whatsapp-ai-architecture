package com.securewa.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberNormalizerTest {

    private fun assertNormalized(
        input: String,
        expected: String,
        callingCode: String,
        national: String,
        region: String,
        defaultRegion: String? = null
    ) {
        val result = PhoneNumberNormalizer.normalize(input, defaultRegion)
        assertTrue(
            "expected success for '$input' but got $result",
            result is NormalizationResult.Success
        )
        val number = (result as NormalizationResult.Success).number
        assertEquals("e164 for '$input'", expected, number.e164)
        assertEquals("calling code for '$input'", callingCode, number.countryCallingCode)
        assertEquals("national number for '$input'", national, number.nationalSignificantNumber)
        assertEquals("region for '$input'", region, number.regionCode)
    }

    private fun assertRejected(input: String, expected: NormalizationFailure, defaultRegion: String? = null) {
        val result = PhoneNumberNormalizer.normalize(input, defaultRegion)
        assertTrue(
            "expected $expected for '$input' but got $result",
            result is NormalizationResult.Failure && result.reason == expected
        )
    }

    @Test
    fun `international input with separators is normalised`() {
        assertNormalized("+92 300 1234567", "+923001234567", "92", "3001234567", "PK")
        assertNormalized("+1 (555) 234-5678", "+15552345678", "1", "5552345678", "US")
        assertNormalized("+44.20.7946.0958", "+442079460958", "44", "2079460958", "GB")
    }

    @Test
    fun `national format is interpreted using the default region`() {
        assertNormalized("03001234567", "+923001234567", "92", "3001234567", "PK", defaultRegion = "PK")
        assertNormalized("3001234567", "+923001234567", "92", "3001234567", "PK", defaultRegion = "PK")
        assertNormalized("2025550179", "+12025550179", "1", "2025550179", "US", defaultRegion = "US")
    }

    @Test
    fun `double zero international prefix is accepted`() {
        assertNormalized("00923001234567", "+923001234567", "92", "3001234567", "PK")
    }

    @Test
    fun `normalising an already normalised number is idempotent`() {
        val once = PhoneNumberNormalizer.normalize("+923001234567")
        val twice = PhoneNumberNormalizer.normalize((once as NormalizationResult.Success).number.e164)
        assertEquals(once, twice)
    }

    @Test
    fun `missing default region is reported rather than guessed`() {
        assertRejected("03001234567", NormalizationFailure.MISSING_COUNTRY_CODE)
    }

    @Test
    fun `unknown default region is reported`() {
        assertRejected("03001234567", NormalizationFailure.UNKNOWN_DEFAULT_REGION, defaultRegion = "ZZ")
    }

    @Test
    fun `unassigned country calling codes are rejected`() {
        assertRejected("+9991234567", NormalizationFailure.UNKNOWN_COUNTRY_CALLING_CODE)
    }

    @Test
    fun `length limits follow E164`() {
        assertRejected("+923", NormalizationFailure.TOO_SHORT)
        assertRejected("+9230012345678901", NormalizationFailure.TOO_LONG)
    }

    @Test
    fun `NANP numbers must be eleven digits with a valid area and office code`() {
        assertRejected("+1555234567", NormalizationFailure.INVALID_LENGTH_FOR_REGION)
        assertRejected("+10552345678", NormalizationFailure.INVALID_LENGTH_FOR_REGION)
        assertRejected("+15550345678", NormalizationFailure.INVALID_LENGTH_FOR_REGION)
    }

    @Test
    fun `blank and malformed input is rejected`() {
        assertRejected("", NormalizationFailure.EMPTY_INPUT)
        assertRejected("   ", NormalizationFailure.EMPTY_INPUT)
        assertRejected("+92 300 abc", NormalizationFailure.INVALID_CHARACTERS)
        assertRejected("9+23001234567", NormalizationFailure.INVALID_CHARACTERS)
    }

    @Test
    fun `isE164 agrees with normalize`() {
        assertTrue(PhoneNumberNormalizer.isE164("+923001234567"))
        assertTrue(!PhoneNumberNormalizer.isE164("nonsense"))
    }

    @Test
    fun `digits helper strips the plus sign`() {
        assertEquals("923001234567", PhoneNumberNormalizer.digitsOf("+92 300 1234567"))
        assertEquals(null, PhoneNumberNormalizer.digitsOf("+9991234567"))
    }

    @Test
    fun `duplicate detection compares on the canonical form`() {
        val a = PhoneNumberNormalizer.normalize("03001234567", "PK")
        val b = PhoneNumberNormalizer.normalize("+923001234567")
        val c = PhoneNumberNormalizer.normalize("0092-300-1234567")
        assertEquals(a, b)
        assertEquals(b, c)
    }
}
