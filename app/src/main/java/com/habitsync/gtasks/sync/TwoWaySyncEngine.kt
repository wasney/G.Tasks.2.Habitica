package com.habitsync.gtasks.sync

import com.habitsync.gtasks.data.local.ChecklistMode
import com.habitsync.gtasks.data.local.ConflictPolicy
import com.habitsync.gtasks.data.local.DeletionPolicy
import com.habitsync.gtasks.data.local.SubtaskSyncEntity
import com.habitsync.gtasks.data.local.SyncDao
import com.habitsync.gtasks.data.local.SyncDirection
import com.habitsync.gtasks.data.local.SyncLogEntity
import com.habitsync.gtasks.data.local.SyncPreferences
import com.habitsync.gtasks.data.local.TaskScope
import com.habitsync.gtasks.data.local.TaskSyncEntity
import com.habitsync.gtasks.data.remote.GoogleTaskDto
import com.habitsync.gtasks.data.remote.GoogleTasksApiService
import com.habitsync.gtasks.data.remote.HabiticaApiService
import com.habitsync.gtasks.data.remote.HabiticaChecklistItemDto
import com.habitsync.gtasks.data.remote.HabiticaTaskDto
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Full Two-Way Synchronization Engine between Habitica (To-Dos + Dailies + Checklists)
 * and Google Tasks (Tasks + Indented Subtasks + Due Dates + Notes).
 * Respects all user-configurable settings in [SyncPreferences].
 */
class TwoWaySyncEngine(
    private val prefs: SyncPreferences,
    private val dao: SyncDao,
    private val habiticaApi: HabiticaApiService,
    private val googleApi: GoogleTasksApiService
) {

    suspend fun runFullSync(): Result<Int> {
        if (!prefs.isHabiticaConnected() || !prefs.isGoogleConnected()) {
            log("ERROR", "Sync skipped: Habitica or Google Tasks account not signed in.")
            return Result.failure(IllegalStateException("Accounts not signed in"))
        }

        val settings = prefs.getSettings()
        val habUser = prefs.habiticaUserId
        val habKey = prefs.habiticaApiToken
        val clientHeader = "$habUser-HabitSyncAndroid"
        val bearer = "Bearer ${prefs.googleAccessToken}"

        var operationsCount = 0

        try {
            // 1. Fetch Habitica To-Dos & Dailies based on user TaskScope preference
            val habiticaTasks = mutableListOf<HabiticaTaskDto>()
            if (settings.taskScope == TaskScope.TODOS_AND_DAILIES || settings.taskScope == TaskScope.TODOS_ONLY) {
                val todosRes = habiticaApi.getUserTasks(habUser, habKey, clientHeader, "todos")
                if (todosRes.isSuccessful) {
                    habiticaTasks.addAll(todosRes.body()?.data ?: emptyList())
                }
            }
            if (settings.taskScope == TaskScope.TODOS_AND_DAILIES || settings.taskScope == TaskScope.DAILIES_ONLY) {
                if (settings.respectHabiticaRateLimits) delay(350)
                val dailiesRes = habiticaApi.getUserTasks(habUser, habKey, clientHeader, "dailys")
                if (dailiesRes.isSuccessful) {
                    habiticaTasks.addAll(dailiesRes.body()?.data ?: emptyList())
                }
            }

            // 2. Fetch Google Tasks from the mapped To-Do list and Daily list
            val todoListTasks = googleApi.getTasks(bearer, settings.googleTodoListId).body()?.items ?: emptyList()
            val dailyListTasks = if (settings.googleDailyListId == settings.googleTodoListId) {
                todoListTasks
            } else {
                googleApi.getTasks(bearer, settings.googleDailyListId).body()?.items ?: emptyList()
            }

            val allGoogleTasksById = (todoListTasks + dailyListTasks)
                .filter { it.id != null }
                .associateBy { it.id!! }

            val existingLinks = dao.getAllActiveLinks().associateBy { it.habiticaTaskId }
            val existingLinksByGoogleId = existingLinks.values.associateBy { it.googleTaskId }

            // 3. Reconcile Existing Linked Tasks (Two-Way Updates, Completions, Dailies Cron Reset, Deletions)
            for (link in existingLinks.values) {
                val hTask = habiticaTasks.find { it.id == link.habiticaTaskId }
                val gTask = allGoogleTasksById[link.googleTaskId]

                // Case A: Deleted in one or both apps
                if (hTask == null || gTask == null || gTask.deleted == true) {
                    handleDeletionPolicy(
                        link = link,
                        hTask = hTask,
                        gTask = gTask,
                        policy = settings.deletionPolicy,
                        habUser = habUser,
                        habKey = habKey,
                        clientHeader = clientHeader,
                        bearer = bearer
                    )
                    operationsCount++
                    continue
                }

                // Case B: Both exist -> Compare completion & fields according to SyncDirection and ConflictPolicy
                val hCompleted = hTask.completed
                val gCompleted = gTask.status == "completed"

                // Special Habitica Daily Cron Reset Check:
                // If a Daily was previously completed in both apps, and Habitica's midnight cron reset hCompleted to false:
                if (link.taskType == "daily" && link.lastCompletedState && !hCompleted && gCompleted) {
                    val nextDueIso = hTask.nextDue?.firstOrNull() ?: link.lastSyncedDueDate
                    googleApi.patchTask(
                        bearer,
                        link.googleTaskListId,
                        gTask.id!!,
                        gTask.copy(status = "needsAction", due = nextDueIso)
                    )
                    dao.upsertTaskLink(link.copy(lastCompletedState = false, lastSyncedDueDate = nextDueIso))
                    log("H->G", "Daily '${hTask.text}' reset by Habitica cron -> unchecked Google Task for new day.")
                    operationsCount++
                    continue
                }

                // Completion state changed on one side
                if (hCompleted != gCompleted) {
                    if (gCompleted != link.lastCompletedState && settings.syncDirection != SyncDirection.HABITICA_TO_GOOGLE) {
                        // Completed/uncompleted in Google Tasks on phone -> score in Habitica
                        val dir = if (gCompleted) "up" else "down"
                        habiticaApi.scoreTask(habUser, habKey, clientHeader, hTask.id!!, dir)
                        dao.upsertTaskLink(link.copy(lastCompletedState = gCompleted))
                        log("G->H", "Synced completion ($dir) from Google Tasks to Habitica '${hTask.text}'.")
                        operationsCount++
                    } else if (hCompleted != link.lastCompletedState && settings.syncDirection != SyncDirection.GOOGLE_TO_HABITICA) {
                        // Completed/uncompleted in Habitica -> update Google Task
                        val newStatus = if (hCompleted) "completed" else "needsAction"
                        googleApi.patchTask(bearer, link.googleTaskListId, gTask.id!!, gTask.copy(status = newStatus))
                        dao.upsertTaskLink(link.copy(lastCompletedState = hCompleted))
                        log("H->G", "Synced completion ($newStatus) from Habitica to Google Tasks '${gTask.title}'.")
                        operationsCount++
                    }
                }

                // Title, Notes & Due Date comparison
                val hUpdatedMs = parseIsoMillis(hTask.updatedAt)
                val gUpdatedMs = parseIsoMillis(gTask.updated)
                val hChanged = hTask.text != link.lastSyncedTitle || (hTask.notes ?: "") != link.lastSyncedNotes
                val gChanged = gTask.title != link.lastSyncedTitle || (gTask.notes ?: "") != link.lastSyncedNotes

                if (hChanged || gChanged) {
                    val pushHabiticaToGoogle = when {
                        settings.syncDirection == SyncDirection.HABITICA_TO_GOOGLE -> true
                        settings.syncDirection == SyncDirection.GOOGLE_TO_HABITICA -> false
                        hChanged && !gChanged -> true
                        gChanged && !hChanged -> false
                        settings.conflictPolicy == ConflictPolicy.HABITICA_WINS -> true
                        settings.conflictPolicy == ConflictPolicy.GOOGLE_WINS -> false
                        else -> hUpdatedMs >= gUpdatedMs // NEWEST_WINS
                    }

                    if (pushHabiticaToGoogle) {
                        val dueDate = hTask.date ?: hTask.nextDue?.firstOrNull()
                        googleApi.patchTask(
                            bearer,
                            link.googleTaskListId,
                            gTask.id!!,
                            gTask.copy(title = hTask.text, notes = hTask.notes ?: "", due = dueDate)
                        )
                        dao.upsertTaskLink(
                            link.copy(
                                lastSyncedTitle = hTask.text,
                                lastSyncedNotes = hTask.notes ?: "",
                                lastSyncedDueDate = dueDate,
                                lastHabiticaUpdatedMs = hUpdatedMs,
                                lastGoogleUpdatedMs = System.currentTimeMillis()
                            )
                        )
                        log("H->G", "Updated Google Task '${hTask.text}' details from Habitica.")
                        operationsCount++
                    } else {
                        habiticaApi.updateTask(
                            habUser,
                            habKey,
                            clientHeader,
                            hTask.id!!,
                            hTask.copy(text = gTask.title, notes = gTask.notes ?: "", date = gTask.due)
                        )
                        dao.upsertTaskLink(
                            link.copy(
                                lastSyncedTitle = gTask.title,
                                lastSyncedNotes = gTask.notes ?: "",
                                lastSyncedDueDate = gTask.due,
                                lastHabiticaUpdatedMs = System.currentTimeMillis(),
                                lastGoogleUpdatedMs = gUpdatedMs
                            )
                        )
                        log("G->H", "Updated Habitica '${gTask.title}' details from Google Tasks.")
                        operationsCount++
                    }
                }

                // 4. Sync Subtasks / Checklists if enabled
                if (settings.checklistMode == ChecklistMode.NATIVE_SUBTASKS) {
                    syncChecklistsAndSubtasks(
                        link = link,
                        hTask = hTask,
                        allListTasks = if (link.taskType == "daily") dailyListTasks else todoListTasks,
                        habUser = habUser,
                        habKey = habKey,
                        clientHeader = clientHeader,
                        bearer = bearer
                    )
                }
            }

            // 5. Discover New Unlinked Habitica Tasks -> Create in Google Tasks
            if (settings.syncDirection != SyncDirection.GOOGLE_TO_HABITICA) {
                for (hTask in habiticaTasks) {
                    val hId = hTask.id ?: continue
                    if (existingLinks.containsKey(hId)) continue
                    val targetListId = if (hTask.type == "daily") settings.googleDailyListId else settings.googleTodoListId
                    val dueIso = hTask.date ?: hTask.nextDue?.firstOrNull()
                    val createdG = googleApi.insertTask(
                        bearer,
                        targetListId,
                        parentTaskId = null,
                        GoogleTaskDto(
                            title = hTask.text,
                            notes = hTask.notes ?: "",
                            status = if (hTask.completed) "completed" else "needsAction",
                            due = dueIso
                        )
                    ).body()

                    if (createdG?.id != null) {
                        val newLink = TaskSyncEntity(
                            habiticaTaskId = hId,
                            googleTaskId = createdG.id,
                            googleTaskListId = targetListId,
                            taskType = hTask.type,
                            lastSyncedTitle = hTask.text,
                            lastSyncedNotes = hTask.notes ?: "",
                            lastSyncedDueDate = dueIso,
                            lastCompletedState = hTask.completed,
                            lastHabiticaUpdatedMs = parseIsoMillis(hTask.updatedAt),
                            lastGoogleUpdatedMs = parseIsoMillis(createdG.updated)
                        )
                        dao.upsertTaskLink(newLink)
                        log("H->G", "Created new Google Task '${hTask.text}' from Habitica ${hTask.type}.")
                        operationsCount++
                    }
                }
            }

            // 6. Discover New Top-Level Google Tasks -> Create in Habitica
            if (settings.syncDirection != SyncDirection.HABITICA_TO_GOOGLE) {
                val topLevelGoogleTasks = (todoListTasks.map { it to "todo" } + dailyListTasks.map { it to "daily" })
                    .filter { (g, _) -> g.id != null && g.parent == null && g.deleted != true }

                for ((gTask, inferredType) in topLevelGoogleTasks) {
                    val gId = gTask.id!!
                    if (existingLinksByGoogleId.containsKey(gId)) continue
                    val createdH = habiticaApi.createTask(
                        habUser,
                        habKey,
                        clientHeader,
                        HabiticaTaskDto(
                            type = inferredType,
                            text = gTask.title,
                            notes = gTask.notes ?: "",
                            date = gTask.due
                        )
                    ).body()?.data

                    if (createdH?.id != null) {
                        val listId = if (inferredType == "daily") settings.googleDailyListId else settings.googleTodoListId
                        dao.upsertTaskLink(
                            TaskSyncEntity(
                                habiticaTaskId = createdH.id,
                                googleTaskId = gId,
                                googleTaskListId = listId,
                                taskType = inferredType,
                                lastSyncedTitle = gTask.title,
                                lastSyncedNotes = gTask.notes ?: "",
                                lastSyncedDueDate = gTask.due,
                                lastCompletedState = gTask.status == "completed",
                                lastHabiticaUpdatedMs = parseIsoMillis(createdH.updatedAt),
                                lastGoogleUpdatedMs = parseIsoMillis(gTask.updated)
                            )
                        )
                        log("G->H", "Created new Habitica $inferredType '${gTask.title}' from Google Tasks.")
                        operationsCount++
                    }
                }
            }

            log("BOTH", "Sync cycle finished cleanly ($operationsCount updates).")
            return Result.success(operationsCount)
        } catch (e: Exception) {
            log("ERROR", "Sync error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            return Result.failure(e)
        }
    }

    private suspend fun syncChecklistsAndSubtasks(
        link: TaskSyncEntity,
        hTask: HabiticaTaskDto,
        allListTasks: List<GoogleTaskDto>,
        habUser: String,
        habKey: String,
        clientHeader: String,
        bearer: String
    ) {
        val checklist = hTask.checklist ?: emptyList()
        val googleSubtasks = allListTasks.filter { it.parent == link.googleTaskId && it.deleted != true }
        val subLinks = dao.getSubtaskLinks(link.habiticaTaskId).associateBy { it.habiticaChecklistItemId }
        val subLinksByGId = subLinks.values.associateBy { it.googleSubtaskId }

        // Push new Habitica checklist items to Google Subtasks
        for (item in checklist) {
            val itemId = item.id ?: continue
            val existingSub = subLinks[itemId]
            if (existingSub == null) {
                val createdSub = googleApi.insertTask(
                    bearer,
                    link.googleTaskListId,
                    parentTaskId = link.googleTaskId,
                    GoogleTaskDto(
                        title = item.text,
                        status = if (item.completed) "completed" else "needsAction",
                        parent = link.googleTaskId
                    )
                ).body()
                if (createdSub?.id != null) {
                    dao.upsertSubtaskLink(
                        SubtaskSyncEntity(
                            parentHabiticaId = link.habiticaTaskId,
                            habiticaChecklistItemId = itemId,
                            googleSubtaskId = createdSub.id,
                            googleTaskListId = link.googleTaskListId,
                            lastTitle = item.text,
                            lastCompleted = item.completed
                        )
                    )
                }
            } else {
                val matchingGSub = googleSubtasks.find { it.id == existingSub.googleSubtaskId }
                if (matchingGSub != null) {
                    val gSubDone = matchingGSub.status == "completed"
                    if (gSubDone != existingSub.lastCompleted) {
                        habiticaApi.scoreChecklistItem(habUser, habKey, clientHeader, link.habiticaTaskId, itemId)
                        dao.upsertSubtaskLink(existingSub.copy(lastCompleted = gSubDone))
                    } else if (item.completed != existingSub.lastCompleted) {
                        googleApi.patchTask(
                            bearer,
                            link.googleTaskListId,
                            matchingGSub.id!!,
                            matchingGSub.copy(status = if (item.completed) "completed" else "needsAction")
                        )
                        dao.upsertSubtaskLink(existingSub.copy(lastCompleted = item.completed))
                    }
                }
            }
        }

        // Pull new Google Subtasks into Habitica Checklist
        for (gSub in googleSubtasks) {
            val gSubId = gSub.id ?: continue
            if (!subLinksByGId.containsKey(gSubId)) {
                val updatedTask = habiticaApi.addChecklistItem(
                    habUser,
                    habKey,
                    clientHeader,
                    link.habiticaTaskId,
                    HabiticaChecklistItemDto(text = gSub.title, completed = gSub.status == "completed")
                ).body()?.data
                val newlyCreatedItem = updatedTask?.checklist?.lastOrNull()
                if (newlyCreatedItem?.id != null) {
                    dao.upsertSubtaskLink(
                        SubtaskSyncEntity(
                            parentHabiticaId = link.habiticaTaskId,
                            habiticaChecklistItemId = newlyCreatedItem.id,
                            googleSubtaskId = gSubId,
                            googleTaskListId = link.googleTaskListId,
                            lastTitle = gSub.title,
                            lastCompleted = gSub.status == "completed"
                        )
                    )
                }
            }
        }
    }

    private suspend fun handleDeletionPolicy(
        link: TaskSyncEntity,
        hTask: HabiticaTaskDto?,
        gTask: GoogleTaskDto?,
        policy: DeletionPolicy,
        habUser: String,
        habKey: String,
        clientHeader: String,
        bearer: String
    ) {
        when (policy) {
            DeletionPolicy.DELETE_BOTH -> {
                if (hTask != null) {
                    habiticaApi.deleteTask(habUser, habKey, clientHeader, link.habiticaTaskId)
                }
                if (gTask != null && gTask.deleted != true) {
                    googleApi.deleteTask(bearer, link.googleTaskListId, link.googleTaskId)
                }
                dao.deleteTaskLink(link.habiticaTaskId)
                log("BOTH", "Mirrored deletion for '${link.lastSyncedTitle}' across both apps.")
            }
            DeletionPolicy.UNLINK_KEEP -> {
                dao.upsertTaskLink(link.copy(isUnlinked = true))
                log("BOTH", "Unlinked '${link.lastSyncedTitle}' safely (counterpart task kept intact).")
            }
            DeletionPolicy.MARK_COMPLETED -> {
                if (hTask != null && !hTask.completed) {
                    habiticaApi.scoreTask(habUser, habKey, clientHeader, link.habiticaTaskId, "up")
                }
                if (gTask != null && gTask.status != "completed") {
                    googleApi.patchTask(bearer, link.googleTaskListId, link.googleTaskId, gTask.copy(status = "completed"))
                }
                dao.deleteTaskLink(link.habiticaTaskId)
                log("BOTH", "Marked counterpart of deleted task '${link.lastSyncedTitle}' as completed.")
            }
            DeletionPolicy.RECREATE -> {
                if (hTask == null && gTask != null) {
                    habiticaApi.createTask(
                        habUser,
                        habKey,
                        clientHeader,
                        HabiticaTaskDto(type = link.taskType, text = gTask.title, notes = gTask.notes)
                    )
                    log("G->H", "Re-created deleted Habitica task '${gTask.title}' from Google Tasks.")
                } else if (gTask == null && hTask != null) {
                    googleApi.insertTask(
                        bearer,
                        link.googleTaskListId,
                        null,
                        GoogleTaskDto(title = hTask.text, notes = hTask.notes)
                    )
                    log("H->G", "Re-created deleted Google Task '${hTask.text}' from Habitica.")
                }
            }
        }
    }

    private suspend fun log(direction: String, message: String) {
        dao.insertLog(
            SyncLogEntity(
                timestampMs = System.currentTimeMillis(),
                direction = direction,
                message = message
            )
        )
    }

    private fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
