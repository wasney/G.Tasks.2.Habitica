package com.habitsync.gtasks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.habitsync.gtasks.data.local.ChecklistMode
import com.habitsync.gtasks.data.local.ConflictPolicy
import com.habitsync.gtasks.data.local.DeletionPolicy
import com.habitsync.gtasks.data.local.SyncDirection
import com.habitsync.gtasks.data.local.SyncIntervalOption
import com.habitsync.gtasks.data.local.SyncPreferences
import com.habitsync.gtasks.data.local.TaskScope
import com.habitsync.gtasks.data.local.UserSyncSettings
import com.habitsync.gtasks.sync.PeriodicSyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SyncPreferences(this)
        PeriodicSyncWorker.scheduleOrUpdate(this, prefs)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HabitSyncApp(
                    prefs = prefs,
                    onSettingsUpdated = { PeriodicSyncWorker.scheduleOrUpdate(this, prefs) },
                    onSyncNow = { PeriodicSyncWorker.triggerImmediateSync(this) }
                )
            }
        }
    }
}

@Composable
fun HabitSyncApp(
    prefs: SyncPreferences,
    onSettingsUpdated: () -> Unit,
    onSyncNow: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(prefs.getSettings()) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Sync & Accounts") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Sync Settings") })
            }

            when (selectedTab) {
                0 -> AccountsAndSyncScreen(prefs = prefs, settings = settings, onSyncNow = onSyncNow)
                1 -> UserConfigurableSettingsScreen(
                    settings = settings,
                    onSave = { updated ->
                        settings = updated
                        prefs.saveSettings(updated)
                        onSettingsUpdated()
                    }
                )
            }
        }
    }
}

@Composable
fun AccountsAndSyncScreen(
    prefs: SyncPreferences,
    settings: UserSyncSettings,
    onSyncNow: () -> Unit
) {
    var habUserId by remember { mutableStateOf(prefs.habiticaUserId) }
    var habToken by remember { mutableStateOf(prefs.habiticaApiToken) }
    var googleEmail by remember { mutableStateOf(prefs.googleAccountEmail) }
    var googleToken by remember { mutableStateOf(prefs.googleAccessToken) }
    var statusBanner by remember { mutableStateOf("Ready to sync Habitica To-Dos, Dailies & Subtasks") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HabitSync Two-Way Controller", style = MaterialTheme.typography.titleMedium)
                Text("Mode: ${settings.syncDirection.displayName}", style = MaterialTheme.typography.bodySmall)
                Text("Interval: ${settings.syncInterval.displayName}", style = MaterialTheme.typography.bodySmall)
                Text("Deletions: ${settings.deletionPolicy.displayName}", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        onSyncNow()
                        statusBanner = "Triggered immediate 2-way WorkManager sync!"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync Now (To-Dos, Dailies & Subtasks)")
                }
                Text(statusBanner, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Multi-User Habitica Sign-In Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Habitica Account (Per-User Login)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = habUserId,
                    onValueChange = { habUserId = it },
                    label = { Text("Habitica User ID (UUID)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = habToken,
                    onValueChange = { habToken = it },
                    label = { Text("Habitica API Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.habiticaUserId = habUserId
                        prefs.habiticaApiToken = habToken
                        statusBanner = "Saved Habitica credentials to EncryptedSharedPreferences."
                    }) {
                        Text("Save Habitica Login")
                    }
                    OutlinedButton(onClick = {
                        prefs.clearHabiticaAccount()
                        habUserId = ""
                        habToken = ""
                        statusBanner = "Logged out of Habitica."
                    }) {
                        Text("Log Out")
                    }
                }
            }
        }

        // Multi-User Google Tasks Sign-In Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Google Tasks Account (Per-User OAuth)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = googleEmail,
                    onValueChange = { googleEmail = it },
                    label = { Text("Google Account Email") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = googleToken,
                    onValueChange = { googleToken = it },
                    label = { Text("OAuth 2.0 Token (Auto-filled by GoogleSignIn)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.googleAccountEmail = googleEmail
                        prefs.googleAccessToken = googleToken
                        statusBanner = "Connected Google Tasks account ($googleEmail)."
                    }) {
                        Text("Connect Google Tasks")
                    }
                    OutlinedButton(onClick = {
                        prefs.clearGoogleAccount()
                        googleEmail = ""
                        googleToken = ""
                        statusBanner = "Disconnected Google Tasks."
                    }) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

@Composable
fun UserConfigurableSettingsScreen(
    settings: UserSyncSettings,
    onSave: (UserSyncSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("User-Configurable Sync Preferences", style = MaterialTheme.typography.titleLarge)

        EnumDropdownSetting(
            label = "1. Background Sync Interval",
            currentLabel = settings.syncInterval.displayName,
            options = SyncIntervalOption.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(syncInterval = it)) }
        )

        EnumDropdownSetting(
            label = "2. Sync Direction Mode",
            currentLabel = settings.syncDirection.displayName,
            options = SyncDirection.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(syncDirection = it)) }
        )

        EnumDropdownSetting(
            label = "3. Deletion Behavior (When a Task is Deleted)",
            currentLabel = settings.deletionPolicy.displayName,
            options = DeletionPolicy.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(deletionPolicy = it)) }
        )

        EnumDropdownSetting(
            label = "4. Conflict Resolution Strategy",
            currentLabel = settings.conflictPolicy.displayName,
            options = ConflictPolicy.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(conflictPolicy = it)) }
        )

        EnumDropdownSetting(
            label = "5. Task Scope (To-Dos & Dailies)",
            currentLabel = settings.taskScope.displayName,
            options = TaskScope.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(taskScope = it)) }
        )

        EnumDropdownSetting(
            label = "6. Subtasks & Checklists Mode",
            currentLabel = settings.checklistMode.displayName,
            options = ChecklistMode.entries,
            optionLabel = { it.displayName },
            onSelect = { onSave(settings.copy(checklistMode = it)) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sync on Unmetered Wi-Fi Only")
            Switch(
                checked = settings.wifiOnly,
                onCheckedChange = { onSave(settings.copy(wifiOnly = it)) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Respect Habitica 30s Rate-Limit Pacing")
            Switch(
                checked = settings.respectHabiticaRateLimits,
                onCheckedChange = { onSave(settings.copy(respectHabiticaRateLimits = it)) }
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun <T> EnumDropdownSetting(
    label: String,
    currentLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(currentLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}
