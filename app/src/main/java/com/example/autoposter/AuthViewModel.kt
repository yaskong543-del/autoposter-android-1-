package com.example.autoposter

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A message for the UI: a string resource plus an optional server detail appended for unknown errors. */
data class UiMessage(@StringRes val res: Int, val detail: String? = null)

data class AuthUiState(
    val busy: Boolean = false, val error: UiMessage? = null, val info: UiMessage? = null,
    val pendingEmail: String? = null, val resetEmail: String? = null
)

enum class AuthEvent { GO_VERIFY, GO_RESET_CODE }

fun <T : ViewModel> containerFactory(c: AppContainer, build: (AppContainer) -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = build(c) as V
}

fun messageFor(e: Throwable): UiMessage {
    if (e !is ApiException) return UiMessage(R.string.err_generic)
    return when (e.errorCode) {
        "invalid_credentials" -> UiMessage(R.string.err_invalid_credentials)
        "email_not_confirmed" -> UiMessage(R.string.err_email_not_confirmed)
        "user_already_exists", "email_exists" -> UiMessage(R.string.err_user_exists)
        "weak_password" -> UiMessage(R.string.err_weak_password)
        "over_request_rate_limit", "over_email_send_rate_limit" -> UiMessage(R.string.err_rate_limited)
        "otp_expired", "otp_invalid" -> UiMessage(R.string.err_code_invalid)
        "not_configured" -> UiMessage(R.string.err_not_configured)
        else -> when (e.kind) {
            ErrorKind.NETWORK -> UiMessage(R.string.err_network)
            ErrorKind.SERVER -> UiMessage(R.string.err_server)
            ErrorKind.AUTH -> UiMessage(R.string.err_session_expired)
            else -> UiMessage(R.string.err_generic_detail, e.message)
        }
    }
}

class AuthViewModel(private val c: AppContainer) : ViewModel() {
    val authState: StateFlow<AuthState> = c.auth.state
    val backendConfigured: Boolean = c.config.configured
    private val google = GoogleSignInHelper(c.config.googleClientId)
    val googleAvailable: Boolean = google.available

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AuthEvent> = _events

    fun clearMessages() = _ui.update { it.copy(error = null, info = null) }

    private fun fail(@StringRes res: Int) = _ui.update { it.copy(error = UiMessage(res), info = null) }

    private fun work(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLog.w("Auth", "action failed: ${e.javaClass.simpleName}")
                _ui.update { it.copy(error = messageFor(e)) }
            } finally { _ui.update { it.copy(busy = false) } }
        }
    }

    fun signIn(email: String, password: String) {
        if (!Validators.isEmail(email)) return fail(R.string.err_email_invalid)
        if (password.isEmpty()) return fail(R.string.err_password_required)
        work {
            try { c.auth.signIn(email, password) } catch (e: ApiException) {
                if (e.errorCode == "email_not_confirmed") {
                    _ui.update { it.copy(pendingEmail = email.trim()) }; _events.tryEmit(AuthEvent.GO_VERIFY)
                } else throw e
            }
        }
    }

    fun signUp(name: String, email: String, password: String, confirm: String) {
        if (name.isBlank()) return fail(R.string.err_name_required)
        if (!Validators.isEmail(email)) return fail(R.string.err_email_invalid)
        if (!Validators.isStrongEnough(password)) return fail(R.string.err_weak_password)
        if (password != confirm) return fail(R.string.err_password_mismatch)
        work {
            when (val r = c.auth.signUp(email, password, name)) {
                is SignUpResult.NeedsVerification -> { _ui.update { it.copy(pendingEmail = r.email) }; _events.tryEmit(AuthEvent.GO_VERIFY) }
                SignUpResult.SignedIn -> Unit
            }
        }
    }

    fun verifyEmail(code: String) {
        val email = _ui.value.pendingEmail ?: return
        if (!Validators.isOtp(code)) return fail(R.string.err_code_invalid)
        work { c.auth.verifyOtp(email, code, "signup") }
    }

    fun resendVerification() {
        val email = _ui.value.pendingEmail ?: return
        work { c.auth.resendVerification(email); _ui.update { it.copy(info = UiMessage(R.string.info_code_sent)) } }
    }

    fun sendReset(email: String) {
        if (!Validators.isEmail(email)) return fail(R.string.err_email_invalid)
        work {
            c.auth.sendPasswordReset(email)
            _ui.update { it.copy(resetEmail = email.trim(), info = UiMessage(R.string.info_code_sent)) }
            _events.tryEmit(AuthEvent.GO_RESET_CODE)
        }
    }

    fun resendReset() {
        val email = _ui.value.resetEmail ?: return
        work { c.auth.sendPasswordReset(email); _ui.update { it.copy(info = UiMessage(R.string.info_code_sent)) } }
    }

    fun verifyResetCode(code: String) {
        val email = _ui.value.resetEmail ?: return
        if (!Validators.isOtp(code)) return fail(R.string.err_code_invalid)
        work { c.auth.verifyOtp(email, code, "recovery") }
    }

    fun setNewPassword(password: String, confirm: String) {
        if (!Validators.isStrongEnough(password)) return fail(R.string.err_weak_password)
        if (password != confirm) return fail(R.string.err_password_mismatch)
        work { c.auth.updatePassword(password) }
    }

    fun cancelRecovery() { viewModelScope.launch { c.auth.signOut() } }

    fun signInWithGoogle(activityContext: Context) {
        if (!google.available) return fail(R.string.err_google_not_configured)
        work {
            when (val r = google.request(activityContext)) {
                is GoogleResult.Token -> c.auth.signInWithGoogle(r.idToken, r.rawNonce)
                GoogleResult.Cancelled -> Unit
                GoogleResult.NoAccount -> _ui.update { it.copy(error = UiMessage(R.string.err_google_no_account)) }
                GoogleResult.Failed -> _ui.update { it.copy(error = UiMessage(R.string.err_google_failed)) }
            }
        }
    }

    fun continueLocal() { viewModelScope.launch { c.prefs.setLocalMode(true) } }
}
