package helium314.keyboard.voice.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import helium314.keyboard.voice.AppCtx
import helium314.keyboard.latin.R
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

object AuthManager {
    private const val TAG = "AuthManager"

    private fun webClientId(): String =
        AppCtx.getStringRes(R.string.default_web_client_id)

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    val isSignedIn: Boolean get() = currentUser != null

    val displayName: String? get() = currentUser?.displayName

    val email: String? get() = currentUser?.email

    val uid: String? get() = currentUser?.uid

    private const val MAX_TOKEN_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1000L

    suspend fun getIdToken(): String? {
        val user = currentUser ?: return null

        repeat(MAX_TOKEN_RETRIES) { attempt ->
            try {
                // Always force-refresh to avoid using expired cached tokens
                return user.getIdToken(true).await().token
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed (attempt ${attempt + 1}/$MAX_TOKEN_RETRIES)", e)
                if (attempt < MAX_TOKEN_RETRIES - 1) {
                    val delay = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                    Log.d(TAG, "Retrying in ${delay}ms...")
                    kotlinx.coroutines.delay(delay)
                }
            }
        }
        Log.e(TAG, "All $MAX_TOKEN_RETRIES token attempts exhausted")
        return null
    }

    suspend fun signIn(context: Context): Boolean {
        return try {
            val credentialManager = CredentialManager.create(context)

            val result = try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(webClientId())
                    .setFilterByAuthorizedAccounts(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                credentialManager.getCredential(context, request)
            } catch (e: GetCredentialException) {
                Log.w(TAG, "One Tap failed, falling back to Sign In With Google", e)

                val signInOption = GetSignInWithGoogleOption.Builder(webClientId())
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()

                credentialManager.getCredential(context, request)
            }

            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            auth.signInWithCredential(firebaseCredential).await()
            Log.d(TAG, "Sign-in successful: ${currentUser?.email}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
            false
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential state", e)
        }
        Log.d(TAG, "Signed out")
    }
}
