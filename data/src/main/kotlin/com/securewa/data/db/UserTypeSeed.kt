package com.securewa.data.db

import com.securewa.core.model.UserType
import com.securewa.data.db.entity.UserTypeEntity

/**
 * Seeds the user type reference table from the domain enum.
 *
 * The database cannot express "this column must be one of three values" as a
 * check constraint, so the valid values are rows and every number points at one
 * of them with a foreign key. Generating the rows from the enum keeps the two in
 * step: adding a user type to the domain without shipping a migration is then
 * impossible rather than merely discouraged.
 */
object UserTypeSeed {

    fun entities(): List<UserTypeEntity> = UserType.entries.map { type ->
        UserTypeEntity(
            storageKey = type.storageKey,
            displayName = type.displayName,
            defaultRetentionDays = type.defaultRetentionDays,
            sensitivity = type.sensitivity.name
        )
    }

    fun storageKeys(): List<String> = UserType.validStorageKeys()
}
