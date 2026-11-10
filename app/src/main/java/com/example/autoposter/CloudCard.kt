package com.example.autoposter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional cloud backup + web dashboard settings. Off by default; the app works fully without it. */
@Composable
fun CloudCard(signedIn: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = remember { CloudSync.saved(ctx) }
    var on by remember { mutableStateOf(CloudSync.enabled(ctx)) }
    var url by remember { mutableStateOf(start.url) }
    var key by remember { mutableStateOf(start.key) }
    var email by remember { mutableStateOf(start.email) }
    var pass by remember { mutableStateOf(start.password) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun launchWork(block: () -> String) = scope.launch {
        busy = true
        CloudSync.save(ctx, CloudConfig(url, key, email, pass))
        note = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "Something went wrong." } }
        busy = false
    }

    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cloud backup & web dashboard", fontWeight = FontWeight.SemiBold)
                Muted("Optional. Sends clip names, captions and post status to your own Supabase project so you can watch campaigns on a laptop. Account tokens and videos never leave this phone.")
            }
            Switch(on, { on = it; CloudSync.setEnabled(ctx, it) })
        }
        if (on && signedIn) {
            Button({ launchWork { CloudSync.push(ctx) } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Working…" else "Sync now") }
            note?.let { Muted(it) }
            Muted("Uses your AutoPoster account. Syncs automatically after each upload run while this is on.")
        } else if (on) {
            if (CloudDefaults.URL.isEmpty() || CloudDefaults.KEY.isEmpty()) {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Supabase project URL") })
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Publishable (anon) key") })
            }
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") })
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password (6+ characters)") },
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ launchWork { CloudSync.signUp(CloudConfig(url, key, email, pass)) } }, Modifier.weight(1f), enabled = !busy) { Text("Create account") }
                Button({ launchWork { CloudSync.push(ctx) } }, Modifier.weight(1f), enabled = !busy) { Text(if (busy) "Working…" else "Sign in & sync") }
            }
            note?.let { Muted(it) }
            Muted("Syncs automatically after each upload run while this is on.")
        }
    }
}
