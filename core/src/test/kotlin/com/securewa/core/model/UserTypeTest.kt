package com.securewa.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserTypeTest {

    @Test
    fun `all three user types are supported`() {
        assertEquals(3, UserType.entries.size)
        assertEquals(
            listOf("common_user", "doctor", "patient"),
            UserType.validStorageKeys()
        )
    }

    @Test
    fun `storage keys round trip`() {
        UserType.entries.forEach { type ->
            assertEquals(type, UserType.fromStorageKey(type.storageKey))
        }
    }

    @Test
    fun `unknown storage keys are rejected instead of defaulting`() {
        assertNull(UserType.fromStorageKey("nurse"))
        assertNull(UserType.fromStorageKey("Doctor"))
        assertNull(UserType.fromStorageKey(""))
        assertNull(UserType.fromStorageKey(null))
    }

    @Test
    fun `require throws a descriptive error for invalid values`() {
        try {
            UserType.require("admin")
            fail("expected InvalidUserTypeException")
        } catch (error: InvalidUserTypeException) {
            assertTrue(
                "error message must list valid values: ${error.message}",
                error.message!!.contains("doctor") &&
                    error.message!!.contains("patient") &&
                    error.message!!.contains("common_user")
            )
        }
    }

    @Test
    fun `patient data is the most sensitive class`() {
        assertEquals(DataSensitivity.RESTRICTED, UserType.PATIENT.sensitivity)
        assertEquals(DataSensitivity.HIGH, UserType.DOCTOR.sensitivity)
        assertEquals(DataSensitivity.STANDARD, UserType.COMMON_USER.sensitivity)
    }

    @Test
    fun `retention defaults differ per user type`() {
        assertEquals(30, UserType.DOCTOR.defaultRetentionDays)
        assertEquals(90, UserType.PATIENT.defaultRetentionDays)
        assertEquals(14, UserType.COMMON_USER.defaultRetentionDays)
    }
}
