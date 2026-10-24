package com.habitsync.gtasks.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

data class HabiticaLoginRequest(
    val username: String,
    val password: String
)

data class HabiticaAuthResponse(
    val success: Boolean,
    val data: HabiticaAuthData?
)

data class HabiticaAuthData(
    val id: String,
    val apiToken: String,
    val username: String?
)

data class HabiticaChecklistItemDto(
    val id: String? = null,
    val text: String,
    val completed: Boolean = false
)

data class HabiticaTaskDto(
    val id: String? = null,
    val type: String, // "todo" or "daily"
    val text: String,
    val notes: String? = "",
    val completed: Boolean = false,
    val isDue: Boolean? = true,
    val date: String? = null, // ISO-8601 date for To-Dos
    val nextDue: List<String>? = null, // Upcoming dates for Dailies
    val priority: Float = 1.0f,
    val checklist: List<HabiticaChecklistItemDto>? = emptyList(),
    val updatedAt: String? = null
)

data class HabiticaTaskListResponse(
    val success: Boolean,
    val data: List<HabiticaTaskDto>
)

data class HabiticaSingleTaskResponse(
    val success: Boolean,
    val data: HabiticaTaskDto
)

interface HabiticaApiService {

    /**
     * Allows any user (friends/family) to log in with their Habitica username/email and password
     * and receive their personal User ID + API Token without hardcoding credentials.
     */
    @POST("api/v3/user/auth/local/login")
    suspend fun loginWithPassword(
        @Body request: HabiticaLoginRequest
    ): Response<HabiticaAuthResponse>

    @GET("api/v3/tasks/user")
    suspend fun getUserTasks(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Query("type") type: String // "todos" or "dailys"
    ): Response<HabiticaTaskListResponse>

    @POST("api/v3/tasks/user")
    suspend fun createTask(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Body task: HabiticaTaskDto
    ): Response<HabiticaSingleTaskResponse>

    @PUT("api/v3/tasks/{taskId}")
    suspend fun updateTask(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Body task: HabiticaTaskDto
    ): Response<HabiticaSingleTaskResponse>

    @POST("api/v3/tasks/{taskId}/score/{direction}")
    suspend fun scoreTask(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Path("direction") direction: String // "up" or "down"
    ): Response< Map<String, Any> >

    @DELETE("api/v3/tasks/{taskId}")
    suspend fun deleteTask(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String
    ): Response< Map<String, Any> >

    // --- Checklist / Subtask Endpoints ---
    @POST("api/v3/tasks/{taskId}/checklist")
    suspend fun addChecklistItem(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Body item: HabiticaChecklistItemDto
    ): Response<HabiticaSingleTaskResponse>

    @PUT("api/v3/tasks/{taskId}/checklist/{itemId}")
    suspend fun updateChecklistItem(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Path("itemId") itemId: String,
        @Body item: HabiticaChecklistItemDto
    ): Response<HabiticaSingleTaskResponse>

    @POST("api/v3/tasks/{taskId}/checklist/{itemId}/score")
    suspend fun scoreChecklistItem(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Path("itemId") itemId: String
    ): Response<HabiticaSingleTaskResponse>

    @DELETE("api/v3/tasks/{taskId}/checklist/{itemId}")
    suspend fun deleteChecklistItem(
        @Header("x-api-user") userId: String,
        @Header("x-api-key") apiToken: String,
        @Header("x-client") clientHeader: String,
        @Path("taskId") taskId: String,
        @Path("itemId") itemId: String
    ): Response<HabiticaSingleTaskResponse>
}
