package com.securewa.core.provider

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointPolicyTest {

    @Test
    fun `a credential may be sent over https`() {
        assertNull(EndpointPolicy.problemWith("https://api.openai.com/v1"))
    }

    @Test
    fun `a credential may be sent to a provider running on this device`() {
        assertNull(EndpointPolicy.problemWith("http://localhost:11434"))
        assertNull(EndpointPolicy.problemWith("http://127.0.0.1:11434"))
        assertNull(EndpointPolicy.problemWith("http://[::1]:11434"))
    }

    @Test
    fun `a credential may not be sent over plain http to a remote host`() {
        assertNotNull(EndpointPolicy.problemWith("http://api.example.com/v1"))
    }

    @Test
    fun `a credential may not be sent to something that is not a url`() {
        assertNotNull(EndpointPolicy.problemWith("api.openai.com"))
        assertNotNull(EndpointPolicy.problemWith(""))
        assertNotNull(EndpointPolicy.problemWith("ftp://api.example.com"))
    }

    @Test
    fun `surrounding whitespace does not change the verdict`() {
        assertNull(EndpointPolicy.problemWith("  https://api.openai.com/v1\n"))
        assertNotNull(EndpointPolicy.problemWith("  http://api.example.com/v1\n"))
    }

    @Test
    fun `a host that only ends in localhost is still remote`() {
        assertNotNull(EndpointPolicy.problemWith("http://localhost.example.com"))
    }
}
