package com.securewa.architecture.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.securewa.architecture.R

/**
 * The gate in front of the whole application.
 *
 * The entered characters are held in `CharArray` state rather than in a String,
 * and are cleared when the attempt completes, so the passphrase is not left
 * sitting in an immutable object on the heap.
 */
@Composable
fun LockScreen(state: AppLockState) {
    val lockState = state.lockState.collectAsState().value

    when (lockState) {
        LockState.NeedsPassword -> CreatePasswordCard(onCreate = state::createPassword)
        LockState.Locked -> UnlockCard(onUnlock = state::unlock)
        is LockState.Message -> MessageCard(
            text = lockState.text,
            onDismiss = state::dismissMessage
        )
        LockState.Unlocked -> Unit
    }
}

@Composable
private fun UnlockCard(onUnlock: (CharArray) -> Unit) {
    var password by rememberSaveable { mutableStateOf(charArrayOf()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.lock_unlock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = String(password),
                onValueChange = { password = it.toCharArray() },
                label = { Text(stringResource(R.string.lock_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val attempt = password.copyOf()
                    password = charArrayOf()
                    onUnlock(attempt)
                    attempt.fill('\u0000')
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.lock_unlock_action))
            }
        }
    }
}

@Composable
private fun CreatePasswordCard(onCreate: (CharArray, CharArray) -> Unit) {
    var password by rememberSaveable { mutableStateOf(charArrayOf()) }
    var confirmation by rememberSaveable { mutableStateOf(charArrayOf()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.lock_create_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.lock_create_hint, MIN_PASSWORD_LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = String(password),
                onValueChange = { password = it.toCharArray() },
                label = { Text(stringResource(R.string.lock_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = String(confirmation),
                onValueChange = { confirmation = it.toCharArray() },
                label = { Text(stringResource(R.string.lock_confirm_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val first = password.copyOf()
                    val second = confirmation.copyOf()
                    password = charArrayOf()
                    confirmation = charArrayOf()
                    onCreate(first, second)
                    first.fill('\u0000')
                    second.fill('\u0000')
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.lock_create_action))
            }
            Text(
                text = stringResource(R.string.lock_create_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.lock_dismiss))
            }
        }
    }
}
