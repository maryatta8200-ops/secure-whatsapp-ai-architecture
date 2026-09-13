package com.securewa.architecture.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.securewa.architecture.R
import com.securewa.architecture.ui.lock.AppLockState
import com.securewa.architecture.ui.lock.LockScreen
import com.securewa.architecture.ui.lock.LockState

/**
 * The only entry point into the UI.
 *
 * Nothing behind the lock is reachable while the vault is locked: the
 * capability screen is only composed after the vault reports it is unlocked, so
 * there is no route to credential material without the passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(lockState: AppLockState) {
    val state by lockState.lockState.collectAsState()

    when (state) {
        LockState.Unlocked -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = {
                            IconButton(onClick = lockState::lock) {
                                // Text rather than an icon: no vector asset is
                                // shipped yet, and a label is unambiguous.
                                Text(stringResource(R.string.action_lock))
                            }
                        }
                    )
                }
            ) { padding ->
                CapabilityScreen(contentPadding = padding)
            }
        }
        else -> LockScreen(lockState)
    }
}
