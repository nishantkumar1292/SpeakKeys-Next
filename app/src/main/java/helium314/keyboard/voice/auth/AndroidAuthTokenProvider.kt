package helium314.keyboard.voice.auth

import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider

class AndroidAuthTokenProvider : AuthTokenProvider {
    override val isSignedIn: Boolean
        get() = AuthManager.isSignedIn

    override val userEmail: String?
        get() = AuthManager.currentUser?.email

    override suspend fun getIdToken(): String? = AuthManager.getIdToken()
}
