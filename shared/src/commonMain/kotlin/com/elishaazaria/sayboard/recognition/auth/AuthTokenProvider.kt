package com.elishaazaria.sayboard.recognition.auth

/**
 * Platform-agnostic interface for auth token access.
 * On Android, wraps Firebase Auth. On other platforms, wraps the relevant Firebase SDK.
 */
interface AuthTokenProvider {
    val isSignedIn: Boolean
    val userEmail: String?
    suspend fun getIdToken(): String?
}
