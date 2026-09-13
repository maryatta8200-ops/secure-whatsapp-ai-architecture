/**
 * LOCAL SANDBOX SHIM - NOT PART OF THE SHIPPED APPLICATION.
 *
 * The sandbox this project is developed in cannot reach Maven Central, so the
 * real JUnit artifact cannot be downloaded. These stubs reproduce the small
 * part of the JUnit 4 API used by the `:core` tests so that the *same* test
 * sources can be compiled and executed locally with kotlinc, while CI compiles
 * them against the real `junit:junit:4.13.2`.
 *
 * This file is excluded from every Gradle source set; it exists only in
 * tools/local-verify and is never packaged into the application.
 */
package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test(val timeout: Long = 0L)

object Assert {

    @JvmStatic
    fun fail(message: String? = null): Nothing = throw AssertionError(message ?: "assertion failed")

    @JvmStatic
    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionError(message ?: "expected true")
    }

    @JvmStatic
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)

    @JvmStatic
    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionError(message ?: "expected false")
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) = assertFalse(null, condition)

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("expected:<$expected> but was:<$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("$message expected:<$expected> but was:<$actual>")
        }
    }

    @JvmStatic
    fun assertNotEquals(expected: Any?, actual: Any?) {
        if (expected == actual) throw AssertionError("expected values to differ but both were:<$actual>")
    }

    @JvmStatic
    fun assertNull(message: String?, value: Any?) {
        if (value != null) throw AssertionError("$message expected null but was:<$value>")
    }

    @JvmStatic
    fun assertNull(value: Any?) = assertNull(null, value)

    @JvmStatic
    fun assertNotNull(message: String?, value: Any?) {
        if (value == null) throw AssertionError(message ?: "expected non-null value")
    }

    @JvmStatic
    fun assertNotNull(value: Any?) = assertNotNull(null, value)

    @JvmStatic
    fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            throw AssertionError("byte arrays differ: <${expected.joinToString()}> vs <${actual.joinToString()}>")
        }
    }

    @JvmStatic
    fun assertArrayEquals(message: String?, expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            throw AssertionError("$message byte arrays differ")
        }
    }

    /** Runs [runnable] and returns the throwable of type [expected] it threw. */
    @JvmStatic
    fun <T : Throwable> assertThrows(expected: Class<T>, runnable: () -> Unit): T {
        try {
            runnable()
        } catch (thrown: Throwable) {
            if (expected.isInstance(thrown)) return expected.cast(thrown)
            throw AssertionError("expected ${expected.name} but was ${thrown.javaClass.name}: ${thrown.message}")
        }
        throw AssertionError("expected ${expected.name} to be thrown but nothing was")
    }
}
