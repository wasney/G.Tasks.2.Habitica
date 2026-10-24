package com.habitsync.gtasks.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class GoogleTaskListsResponse(
    val items: List<GoogleTaskListDto>? = emptyList()
)

data class GoogleTaskListDto(
    val id: String,
    val title: String
)

data class GoogleTasksListResponse(
    val items: List<GoogleTaskDto>? = emptyList()
)

data class GoogleTaskDto(
    val id: String? = null,
    val title: String,
    val notes: String? = "",
    val status: String = "needsAction", // "needsAction" or "completed"
    val due: String? = null, // RFC 3339 timestamp
    val updated: String? = null,
    val deleted: Boolean? = false,
    val parent: String? = null // Non-null when this Google Task is a Subtask!
)

interface GoogleTasksApiService {

    @GET("tasks/v1/users/@me/lists")
    suspend fun getTaskLists(
        @Header("Authorization") bearerToken: String
    ): Response<GoogleTaskListsResponse>

    @POST("tasks/v1/users/@me/lists")
    suspend fun createTaskList(
        @Header("Authorization") bearerToken: String,
        @Body list: GoogleTaskListDto
    ): Response<GoogleTaskListDto>

    @GET("tasks/v1/lists/{tasklist}/tasks")
    suspend fun getTasks(
        @Header("Authorization") bearerToken: String,
        @Path("tasklist") taskListId: String,
        @Query("showCompleted") showCompleted: Boolean = true,
        @Query("showHidden") showHidden: Boolean = true,
        @Query("showDeleted") showDeleted: Boolean = true,
        @Query("maxResults") maxResults: Int = 100
    ): Response<GoogleTasksListResponse>

    @POST("tasks/v1/lists/{tasklist}/tasks")
    suspend fun insertTask(
        @Header("Authorization") bearerToken: String,
        @Path("tasklist") taskListId: String,
        @Query("parent") parentTaskId: String? = null,
        @Body task: GoogleTaskDto
    ): Response<GoogleTaskDto>

    @PATCH("tasks/v1/lists/{tasklist}/tasks/{task}")
    suspend fun patchTask(
        @Header("Authorization") bearerToken: String,
        @Path("tasklist") taskListId: String,
        @Path("task") taskId: String,
        @Body task: GoogleTaskDto
    ): Response<GoogleTaskDto>

    @DELETE("tasks/v1/lists/{tasklist}/tasks/{task}")
    suspend fun deleteTask(
        @Header("Authorization") bearerToken: String,
        @Path("tasklist") taskListId: String,
        @Path("task") taskId: String
    ): Response<Unit>
}
