package com.securewa.architecture.di

import android.content.Context
import androidx.room.Room
import com.securewa.data.db.SecureWaDatabase
import com.securewa.data.vault.AndroidKeystorePlatformSealer
import com.securewa.data.vault.FileVaultStorage
import com.securewa.data.vault.VaultRepository
import java.io.File

/**
 * Minimal dependency container.
 *
 * Deliberately hand written rather than generated: the application has one flat
 * dependency graph with no scopes, so an annotation processor would add build
 * time, CPU cost and indirection without buying anything this project needs. If
 * the graph ever grows scopes, replace this with a DI framework rather than
 * extending it.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    val vaultRepository: VaultRepository by lazy {
        VaultRepository(
            storage = FileVaultStorage(File(applicationContext.filesDir, "vault")),
            platformSealer = AndroidKeystorePlatformSealer()
        )
    }

    val database: SecureWaDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            SecureWaDatabase::class.java,
            SecureWaDatabase.DATABASE_NAME
        ).build()
    }
}
