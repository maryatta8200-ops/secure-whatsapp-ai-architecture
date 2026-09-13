package com.securewa.app.ui

import com.securewa.core.capability.Capability
import com.securewa.core.capability.FeatureRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "no simulated features" guarantee is a property of the code, not of a
 * review, so it is asserted here.
 */
class CapabilityStatusTest {

    @Test
    fun `unimplemented capabilities are reported as unavailable`() {
        val unavailable = FeatureRegistry.status().filter { !it.available }
        assertTrue(
            "this build must not claim every capability is available",
            unavailable.isNotEmpty()
        )
    }

    @Test
    fun `messaging and provider capabilities are not claimed before they exist`() {
        listOf(
            Capability.AI_PROVIDERS,
            Capability.TWILIO_ADAPTER,
            Capability.INBOUND_RECEIVER,
            Capability.MESSAGE_PIPELINE,
            Capability.CREDENTIAL_VAULT,
            Capability.LOCAL_PERSISTENCE
        ).forEach { capability ->
            assertFalse(
                "${capability.name} must not be reported as available",
                FeatureRegistry.isAvailable(capability)
            )
        }
    }

    @Test
    fun `implemented milestone one capabilities are available`() {
        assertTrue(FeatureRegistry.isAvailable(Capability.DOMAIN_CORE))
        assertTrue(FeatureRegistry.isAvailable(Capability.APP_SHELL))
    }

    @Test
    fun `availability follows the milestone number`() {
        FeatureRegistry.status().forEach { status ->
            assertEquals(
                status.capability.availableFromMilestone <= FeatureRegistry.CURRENT_MILESTONE,
                status.available
            )
        }
    }

    @Test
    fun `status list is stable and complete`() {
        assertEquals(Capability.entries.size, FeatureRegistry.status().size)
        assertEquals(
            "the list must be ordered by milestone so the UI reads as a roadmap",
            FeatureRegistry.status().map { it.availableFromMilestone }.sorted(),
            FeatureRegistry.status().map { it.availableFromMilestone }
        )
    }
}
