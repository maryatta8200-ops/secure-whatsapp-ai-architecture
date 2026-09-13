package com.securewa.core.provider

/**
 * Whether a credential may be sent to a provider's base URL.
 *
 * The API key travels in a header on every request, so the URL matters as much
 * as the key does. TLS is required everywhere except the loopback interface,
 * which is what people run Ollama or vLLM on: those requests never leave the
 * device, so there is nothing on the wire to protect.
 *
 * Refusing is the only safe answer for anything else. Sending a credential over
 * plain HTTP to a remote host would leak it to everyone on the path.
 */
object EndpointPolicy {

    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

    /** `null` when a credential may be sent to [baseUrl], otherwise why it may not. */
    fun problemWith(baseUrl: String): String? {
        val url = baseUrl.trim()
        if (url.isEmpty()) return "the provider has no base URL"

        if (url.startsWith("https://", ignoreCase = true)) return null

        if (!url.startsWith("http://", ignoreCase = true)) {
            return "the base URL is neither http nor https, so no credential can be sent to it"
        }

        val host = hostOf(url)
        return when {
            host == null -> "the base URL has no host, so no credential can be sent to it"
            host in loopbackHosts -> null
            else -> "a credential may only travel over https, except to the loopback interface"
        }
    }

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val authority = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        if (authority.isEmpty()) return null
        return if (authority.startsWith("[")) {
            // IPv6 literal: [::1]:11434
            authority.substringAfter("[").substringBefore("]").lowercase()
        } else {
            authority.substringBefore(":").lowercase()
        }
    }
}
