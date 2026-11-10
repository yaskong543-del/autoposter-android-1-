package com.example.autoposter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private enum class Root { SPLASH, RESET, MAIN, AUTH }

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PostStore.init(applicationContext)
        // Pick up anything still queued from before the app was closed.
        if (PostStore.items.value.any { it.status.active }) Scheduler.kick(applicationContext)
        handleLink(intent)
        setContent { AppRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** Supabase e-mail links (confirm sign-up, reset password) arrive as autoposter://auth/callback#... */
    private fun handleLink(i: Intent?) {
        val data = i?.dataString ?: return
        if (!data.startsWith("autoposter://auth")) return
        lifecycleScope.launch {
            if (container.auth.handleDeepLink(data) != null)
                Toast.makeText(this@MainActivity, getString(R.string.err_link_invalid), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val theme by container.prefs.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val local by container.prefs.localMode.collectAsStateWithLifecycle(false)
    val authVm: AuthViewModel = viewModel(factory = containerFactory(container) { AuthViewModel(it) })
    val state by authVm.authState.collectAsStateWithLifecycle()
    val root = when {
        state is AuthState.Loading -> Root.SPLASH
        state is AuthState.Recovery -> Root.RESET
        state is AuthState.SignedIn || local -> Root.MAIN
        else -> Root.AUTH
    }
    AutoPosterTheme(theme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Crossfade(root, animationSpec = tween(300), label = "root") { r ->
                when (r) {
                    Root.SPLASH -> SplashScreen()
                    Root.RESET -> ResetPasswordScreen(authVm)
                    Root.MAIN -> Studio()
                    Root.AUTH -> AuthNavHost(authVm)
                }
            }
        }
    }
}
