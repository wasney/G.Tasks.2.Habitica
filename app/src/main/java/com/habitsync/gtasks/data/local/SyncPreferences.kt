package com.habitsync.gtasks.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class SyncIntervalOption(val minutes: Long, val displayName: String) {
    EVERY_15_MIN(15L, "Every 15 Minutes (Fastest Android Periodic)"),
    EVERY_30_MIN(30L, "Every 30 Minutes"),
    EVERY_1_HOUR(60L, "Every 1 Hour"),
    EVERY_2_HOURS(120L, "Every 2 Hours"),
    EVERY_6_HOURS(360L, "Every 6 Hours"),
    MANUAL_ONLY(0L, "Manual 'Sync Now' Only")
}

enum class SyncDirection(val displayName: String) {
    TWO_WAY("Two-Way Bi-Directional (Habitica ↔ Google Tasks)"),
    GOOGLE_TO_HABITICA("One-Way: Google Tasks → Habitica Only"),
    HABITICA_TO_GOOGLE("One-Way: Habitica → Google Tasks Only")
}

enum class DeletionPolicy(val displayName: String) {
    DELETE_BOTH("Mirror Deletion Both Ways (Delete in Counterpart)"),
    UNLINK_KEEP("Safe Unlink (Keep Counterpart Task & Stop Syncing)"),
    MARK_COMPLETED("Complete Counterpart Task Instead of Deleting"),
    RECREATE("Re-create Deleted Task from Counterpart")
}

enum class ConflictPolicy(val displayName: String) {
    NEWEST_WINS("Most Recently Updated Wins (Timestamp Comparison)"),
    HABITICA_WINS("Habitica Always Wins"),
    GOOGLE_WINS("Google Tasks Always Wins")
}

enum class TaskScope(val displayName: String) {
    TODOS_AND_DAILIES("Sync Both Habitica To-Dos & Dailies"),
    TODOS_ONLY("Sync Habitica To-Dos Only"),
    DAILIES_ONLY("Sync Habitica Dailies Only")
}

enum class ChecklistMode(val displayName: String) {
    NATIVE_SUBTASKS("Native Indented Google Subtasks ↔ Habitica Checklists"),
    NOTES_MARKDOWN("Markdown [x]/[ ] Lines Inside Google Task Notes"),
    DISABLED("Ignore Checklists / Subtasks")
}

data class UserSyncSettings(
    val syncInterval: SyncIntervalOption = SyncIntervalOption.EVERY_15_MIN,
    val syncDirection: SyncDirection = SyncDirection.TWO_WAY,
    val deletionPolicy: DeletionPolicy = DeletionPolicy.DELETE_BOTH,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
    val taskScope: TaskScope = TaskScope.TODOS_AND_DAILIES,
    val checklistMode: ChecklistMode = ChecklistMode.NATIVE_SUBTASKS,
    val wifiOnly: Boolean = false,
    val respectHabiticaRateLimits: Boolean = true,
    val googleTodoListId: String = "@default",
    val googleTodoListTitle: String = "My Tasks",
    val googleDailyListId: String = "@default",
    val googleDailyListTitle: String = "Habitica Dailies"
)

class SyncPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "habitsync_encrypted_user_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Multi-User Habitica Credentials ---
    var habiticaUserId: String
        get() = prefs.getString("hab_user_id", "") ?: ""
        set(value) = prefs.edit().putString("hab_user_id", value.trim()).apply()

    var habiticaApiToken: String
        get() = prefs.getString("hab_api_token", "") ?: ""
        set(value) = prefs.edit().putString("hab_api_token", value.trim()).apply()

    var habiticaUsername: String
        get() = prefs.getString("hab_username", "") ?: ""
        set(value) = prefs.edit().putString("hab_username", value.trim()).apply()

    // --- Multi-User Google Account Credentials ---
    var googleAccountEmail: String
        get() = prefs.getString("google_email", "") ?: ""
        set(value) = prefs.edit().putString("google_email", value.trim()).apply()

    var googleAccessToken: String
        get() = prefs.getString("google_access_token", "") ?: ""
        set(value) = prefs.edit().putString("google_access_token", value.trim()).apply()

    fun isHabiticaConnected(): Boolean = habiticaUserId.isNotBlank() && habiticaApiToken.isNotBlank()
    fun isGoogleConnected(): Boolean = googleAccountEmail.isNotBlank()

    fun clearHabiticaAccount() {
        prefs.edit()
            .remove("hab_user_id")
            .remove("hab_api_token")
            .remove("hab_username")
            .apply()
    }

    fun clearGoogleAccount() {
        prefs.edit()
            .remove("google_email")
            .remove("google_access_token")
            .apply()
    }

    // --- User-Configurable Sync Settings ---
    fun getSettings(): UserSyncSettings {
        return UserSyncSettings(
            syncInterval = enumValueOrDefault(prefs.getString("sync_interval", null), SyncIntervalOption.EVERY_15_MIN),
            syncDirection = enumValueOrDefault(prefs.getString("sync_direction", null), SyncDirection.TWO_WAY),
            deletionPolicy = enumValueOrDefault(prefs.getString("deletion_policy", null), DeletionPolicy.DELETE_BOTH),
            conflictPolicy = enumValueOrDefault(prefs.getString("conflict_policy", null), ConflictPolicy.NEWEST_WINS),
            taskScope = enumValueOrDefault(prefs.getString("task_scope", null), TaskScope.TODOS_AND_DAILIES),
            checklistMode = enumValueOrDefault(prefs.getString("checklist_mode", null), ChecklistMode.NATIVE_SUBTASKS),
            wifiOnly = prefs.getBoolean("wifi_only", false),
            respectHabiticaRateLimits = prefs.getBoolean("respect_rate_limits", true),
            googleTodoListId = prefs.getString("google_todo_list_id", "@default") ?: "@default",
            googleTodoListTitle = prefs.getString("google_todo_list_title", "My Tasks") ?: "My Tasks",
            googleDailyListId = prefs.getString("google_daily_list_id", "@default") ?: "@default",
            googleDailyListTitle = prefs.getString("google_daily_list_title", "Habitica Dailies") ?: "Habitica Dailies"
        )
    }

    fun saveSettings(settings: UserSyncSettings) {
        prefs.edit()
            .putString("sync_interval", settings.syncInterval.name)
            .putString("sync_direction", settings.syncDirection.name)
            .putString("deletion_policy", settings.deletionPolicy.name)
            .putString("conflict_policy", settings.conflictPolicy.name)
            .putString("task_scope", settings.taskScope.name)
            .putString("checklist_mode", settings.checklistMode.name)
            .putBoolean("wifi_only", settings.wifiOnly)
            .putBoolean("respect_rate_limits", settings.respectHabiticaRateLimits)
            .putString("google_todo_list_id", settings.googleTodoListId)
            .putString("google_todo_list_title", settings.googleTodoListTitle)
            .putString("google_daily_list_id", settings.googleDailyListId)
            .putString("google_daily_list_title", settings.googleDailyListTitle)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T {
        if (name == null) return default
        return try {
            enumValueOf<T>(name)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
