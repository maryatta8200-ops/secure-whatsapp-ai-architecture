package com.securewa.architecture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.securewa.architecture.di.AppContainer
import com.securewa.architecture.ui.lock.AppLockState
import com.securewa.architecture.ui.theme.SecureWaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecureWaTheme {
                // The container is created once per composition of the activity
                // and owns the vault for the lifetime of the process.
                val container = remember { AppContainer(applicationContext) }
                val lockState = remember { AppLockState(container.vaultRepository) }
                AppRoot(lockState = lockState)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LockScreenPreview() {
    SecureWaTheme {
        // A preview cannot open a real vault, so it renders the locked state
        // only. No simulated unlocked state is shown anywhere in this app.
        CapabilityScreen()
    }
}
