package com.example.autoposter

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface GoogleResult {
    data class Token(val idToken: String, val rawNonce: String) : GoogleResult
    data object Cancelled : GoogleResult
    data object NoAccount : GoogleResult
    data object Failed : GoogleResult
}

/** Google account picker via Credential Manager. The ID token is exchanged with Supabase; nothing else is kept. */
class GoogleSignInHelper(private val webClientId: String) {
    val available: Boolean get() = webClientId.isNotBlank()

    suspend fun request(activityContext: Context): GoogleResult {
        if (!available) return GoogleResult.Failed
        val raw = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hashed = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId).setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).setNonce(hashed).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val cred = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN)
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(cred.data).idToken, raw)
            else GoogleResult.Failed
        } catch (e: GetCredentialCancellationException) { GoogleResult.Cancelled
        } catch (e: NoCredentialException) { GoogleResult.NoAccount
        } catch (e: GetCredentialException) { AppLog.w("Google", "credential error ${e.type}"); GoogleResult.Failed
        } catch (e: Exception) { AppLog.w("Google", "credential error ${e.javaClass.simpleName}"); GoogleResult.Failed }
    }
}
