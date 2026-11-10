package com.example.autoposter

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data class Failed(val message: UiMessage) : LoadState
}

object LanguageManager {
    /** "system", "en" or "hi". AppCompat stores the choice and recreates the activity when it changes. */
    fun current(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (tags.isBlank()) "system" else tags.substringBefore('-').substringBefore(',')
    }
    fun apply(code: String) {
        AppCompatDelegate.setApplicationLocales(if (code == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val authState: StateFlow<AuthState> = c.auth.state
    val themeMode: StateFlow<ThemeMode> = c.prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val localMode: StateFlow<Boolean> = c.prefs.localMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val backendConfigured: Boolean = c.config.configured

    val session: AuthSession? get() = c.auth.currentSession

    val profile: StateFlow<ProfileEntity?> = c.auth.state.flatMapLatest { st ->
        val s = (st as? AuthState.SignedIn)?.session
        if (s == null) flowOf(null) else c.profiles.observe(s.user.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val devices: StateFlow<List<DeviceEntity>> = c.db.devices().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profileLoad = MutableStateFlow<LoadState>(LoadState.Idle)
    val profileLoad: StateFlow<LoadState> = _profileLoad
    private val _devicesLoad = MutableStateFlow<LoadState>(LoadState.Idle)
    val devicesLoad: StateFlow<LoadState> = _devicesLoad
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun dismissMessage() { _message.value = null }

    private fun launchLoad(state: MutableStateFlow<LoadState>, block: suspend () -> Unit) {
        if (state.value == LoadState.Loading) return
        state.value = LoadState.Loading
        viewModelScope.launch {
            try { block(); state.value = LoadState.Idle } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLog.w("Settings", "load failed: ${e.javaClass.simpleName}")
                state.value = LoadState.Failed(messageFor(e))
            }
        }
    }

    private fun action(done: UiMessage? = null, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true; _message.value = null
        viewModelScope.launch {
            try { block(); if (done != null) _message.value = done } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLog.w("Settings", "action failed: ${e.javaClass.simpleName}")
                _message.value = messageFor(e)
            } finally { _busy.value = false }
        }
    }

    fun refreshProfile() {
        if (session == null) return
        launchLoad(_profileLoad) { c.profiles.refresh() }
    }

    fun refreshDevices() {
        if (session == null) return
        launchLoad(_devicesLoad) { c.devices.registerThisDevice(); c.devices.refresh() }
    }

    fun setTheme(m: ThemeMode) { viewModelScope.launch { c.prefs.setThemeMode(m) } }

    fun setLanguage(code: String) {
        viewModelScope.launch { runCatching { c.profiles.updateLanguage(code) } }
        LanguageManager.apply(code)
    }

    fun saveName(name: String) {
        if (name.isBlank()) { _message.value = UiMessage(R.string.err_name_required); return }
        action(UiMessage(R.string.info_saved)) { c.profiles.updateDisplayName(name) }
    }

    fun removeDevice(installId: String) = action(UiMessage(R.string.info_device_removed)) { c.devices.remove(installId) }

    fun signOut(everywhere: Boolean = false) {
        viewModelScope.launch { c.auth.signOut(everywhere) }
    }

    fun sendPasswordResetToSelf() {
        val email = session?.user?.email ?: return
        action(UiMessage(R.string.info_reset_sent)) { c.auth.sendPasswordReset(email) }
    }

    fun deleteAccount() = action {
        c.auth.deleteAccount()
        CloudSync.setEnabled(c.app, false)
        c.prefs.setLocalMode(false)
    }

    fun exitLocalMode() { viewModelScope.launch { c.prefs.setLocalMode(false) } }
}
