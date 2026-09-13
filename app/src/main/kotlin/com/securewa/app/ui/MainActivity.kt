package com.securewa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.securewa.app.ui.theme.SecureWaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecureWaTheme {
                CapabilityScreen()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CapabilityScreenPreview() {
    SecureWaTheme { CapabilityScreen() }
}
