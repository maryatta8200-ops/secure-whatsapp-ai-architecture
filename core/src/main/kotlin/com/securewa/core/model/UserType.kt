package com.securewa.core.model

/**
 * Sensitivity class attached to data handled for a number of that user type.
 * The safety and retention layers use this to decide how long content may be
 * retained and how aggressively it must be redacted.
 */
enum class DataSensitivity {
    STANDARD,
    HIGH,
    RESTRICTED
}

/**
 * The three user types a registered number can be categorised as.
 *
 * The value is persisted through [storageKey], never through the enum ordinal
 * and never through a user-supplied free-text label, so that reordering or
 * renaming the enum can never corrupt stored data.
 */
enum class UserType(
    val storageKey: String,
    val displayName: String,
    val defaultRetentionDays: Int,
    val sensitivity: DataSensitivity
) {
    DOCTOR("doctor", "Doctor", 30, DataSensitivity.HIGH),
    PATIENT("patient", "Patient", 90, DataSensitivity.RESTRICTED),
    COMMON_USER("common_user", "Common user", 14, DataSensitivity.STANDARD);

    companion object {
        private val BY_STORAGE_KEY: Map<String, UserType> = UserType.entries.associateBy { it.storageKey }

        /** Returns the user type for a persisted key, or `null` when invalid. */
        fun fromStorageKey(key: String?): UserType? = if (key == null) null else BY_STORAGE_KEY[key]

        /** Keys accepted by validation. Sorted for stable error messages. */
        fun validStorageKeys(): List<String> = BY_STORAGE_KEY.keys.sorted()

        /** Throws [InvalidUserTypeException] when [key] is not a known user type. */
        fun require(key: String?): UserType = fromStorageKey(key)
            ?: throw InvalidUserTypeException(key?.toString().orEmpty())
    }
}

/**
 * Raised when a user type value that is not one of doctor, patient or
 * common_user is supplied. Persisted user types must always be validated
 * against [UserType.fromStorageKey] before use.
 */
class InvalidUserTypeException(value: String) : IllegalArgumentException(
    "Unsupported user type: '$value'. Supported values: ${UserType.validStorageKeys()}"
)
