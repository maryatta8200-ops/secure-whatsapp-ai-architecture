package com.securewa.architecture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.securewa.architecture.R
import com.securewa.core.capability.FeatureRegistry
import com.securewa.core.capability.FeatureStatus
import com.securewa.core.model.UserType

/**
 * Shows exactly which parts of the product are implemented in this build.
 *
 * The screen is deliberately the first thing the app shows: a capability that
 * is not implemented is rendered as unavailable with the milestone that will
 * deliver it, so no screen can imply that a workflow works before it is built
 * and verified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityScreen() {
    val statuses = remember { FeatureRegistry.status() }
    val userTypes = remember { UserType.entries }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.capability_screen_title)) })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.capability_screen_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                MilestoneHeader()
            }
            item {
                UserTypeCard(userTypes = userTypes)
            }
            items(statuses, key = { it.capability.name }) { status ->
                CapabilityCard(status = status)
            }
        }
    }
}

@Composable
private fun MilestoneHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Milestone ${FeatureRegistry.CURRENT_MILESTONE}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "This build implements milestone ${FeatureRegistry.CURRENT_MILESTONE} " +
                    "only. Messaging, agents, providers and persistence are not yet usable.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun UserTypeCard(userTypes: List<UserType>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.supported_user_types),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            userTypes.forEach { userType ->
                Text(
                    text = "${userType.displayName} (${userType.storageKey})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(status: FeatureStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = status.capability.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(status = status)
            }
            Text(
                text = status.capability.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusChip(status: FeatureStatus) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                if (status.available) {
                    stringResource(R.string.status_available)
                } else {
                    stringResource(
                        R.string.status_unavailable,
                        status.availableFromMilestone
                    )
                }
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (status.available) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    )
}
