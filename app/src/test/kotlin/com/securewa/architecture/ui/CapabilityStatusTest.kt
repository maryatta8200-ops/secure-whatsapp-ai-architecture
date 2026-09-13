package com.securewa.architecture.ui

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
        // Sending a message is the core promise of the product, so it is the
        // capability most likely to be implied by a UI that has not earned it.
        listOf(
            Capability.AI_PROVIDERS,
            Capability.TWILIO_ADAPTER,
            Capability.INBOUND_RECEIVER,
            Capability.MESSAGE_PIPELINE,
            Capability.NUMBER_MANAGEMENT_UI,
            Capability.AGENT_MANAGEMENT_UI,
            Capability.CONVERSATIONS_UI
        ).forEach { capability ->
            assertFalse(
                "${capability.name} must not be reported as available",
                FeatureRegistry.isAvailable(capability)
            )
        }
    }

    @Test
    fun `no capability is claimed ahead of the milestone that delivers it`() {
        FeatureRegistry.status().forEach { status ->
            if (status.capability.availableFromMilestone > FeatureRegistry.CURRENT_MILESTONE) {
                assertFalse(
                    "${status.capability.name} is scheduled for milestone " +
                        "${status.capability.availableFromMilestone} and must not be available",
                    status.available
                )
            }
        }
    }

    @Test
    fun `implemented capabilities are available`() {
        assertTrue(FeatureRegistry.isAvailable(Capability.DOMAIN_CORE))
        assertTrue(FeatureRegistry.isAvailable(Capability.APP_SHELL))
        assertTrue(FeatureRegistry.isAvailable(Capability.LOCAL_PERSISTENCE))
        assertTrue(FeatureRegistry.isAvailable(Capability.APP_LOCK))
        assertTrue(FeatureRegistry.isAvailable(Capability.CREDENTIAL_VAULT))
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
