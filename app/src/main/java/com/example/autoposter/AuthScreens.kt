package com.example.autoposter

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay

/** NavHost with the app-wide transition set. */
@Composable
fun AppNavHost(nav: NavHostController, start: String, modifier: Modifier = Modifier, builder: NavGraphBuilder.() -> Unit) {
    NavHost(
        nav, start, modifier,
        enterTransition = { fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 28 } },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 28 } },
        builder = builder
    )
}

@Composable
private fun msgText(m: UiMessage): String = if (m.detail != null) stringResource(m.res, m.detail) else stringResource(m.res)

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BrandMark(72.dp)
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun AuthScaffold(title: String, subtitle: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent))))
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            else Spacer(Modifier.height(48.dp))
            BrandMark(52.dp)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AppField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null, enabled: Boolean = true
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) },
        shape = MaterialTheme.shapes.medium,
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = if (password) ({
            IconButton({ show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    stringResource(if (show) R.string.auth_hide_password else R.string.auth_show_password))
            }
        }) else null
    )
}

@Composable
private fun Messages(ui: AuthUiState) {
    ui.error?.let { Banner(msgText(it), warning = true) }
    ui.info?.let { Banner(msgText(it), warning = false) }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(52.dp), enabled = enabled && !busy, shape = MaterialTheme.shapes.medium) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        else Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OrDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f)); Text(stringResource(R.string.auth_or), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun GoogleButton(vm: AuthViewModel, busy: Boolean) {
    val ctx = LocalContext.current
    OutlinedButton({ vm.signInWithGoogle(ctx) }, Modifier.fillMaxWidth().height(52.dp), enabled = vm.googleAvailable && !busy, shape = MaterialTheme.shapes.medium) {
        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        Text(stringResource(R.string.auth_google), style = MaterialTheme.typography.labelLarge)
    }
    if (!vm.googleAvailable) Text(stringResource(R.string.auth_google_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun WelcomeScreen(vm: AuthViewModel, onSignIn: () -> Unit, onSignUp: () -> Unit) {
    AuthScaffold(stringResource(R.string.welcome_title), stringResource(R.string.welcome_subtitle), null) {
        listOf(R.string.welcome_point_1, R.string.welcome_point_2, R.string.welcome_point_3).forEach {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, androidx.compose.foundation.shape.CircleShape))
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!vm.backendConfigured) {
            Banner(stringResource(R.string.welcome_not_configured), warning = true)
            PrimaryButton(stringResource(R.string.welcome_local_mode), false) { vm.continueLocal() }
        } else {
            PrimaryButton(stringResource(R.string.welcome_create_account), false, onClick = onSignUp)
            OutlinedButton(onSignIn, Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.auth_sign_in)) }
        }
    }
}

@Composable
fun SignInScreen(vm: AuthViewModel, onBack: () -> Unit, onForgot: () -> Unit, onSignUp: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }
    AuthScaffold(stringResource(R.string.auth_sign_in), stringResource(R.string.auth_sign_in_subtitle), onBack) {
        Messages(ui)
        AppField(email, { email = it }, stringResource(R.string.auth_email), keyboard = KeyboardType.Email, enabled = !ui.busy)
        AppField(password, { password = it }, stringResource(R.string.auth_password), password = true, imeAction = ImeAction.Done,
            onDone = { vm.signIn(email, password) }, enabled = !ui.busy)
        TextButton(onForgot, Modifier.align(Alignment.End)) { Text(stringResource(R.string.auth_forgot)) }
        PrimaryButton(stringResource(R.string.auth_sign_in), ui.busy) { vm.signIn(email, password) }
        OrDivider()
        GoogleButton(vm, ui.busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auth_no_account), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onSignUp) { Text(stringResource(R.string.auth_sign_up)) }
        }
    }
}

@Composable
fun SignUpScreen(vm: AuthViewModel, onBack: () -> Unit, onSignIn: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }
    AuthScaffold(stringResource(R.string.auth_sign_up), stringResource(R.string.auth_sign_up_subtitle), onBack) {
        Messages(ui)
        AppField(name, { name = it }, stringResource(R.string.auth_name), enabled = !ui.busy)
        AppField(email, { email = it }, stringResource(R.string.auth_email), keyboard = KeyboardType.Email, enabled = !ui.busy)
        AppField(password, { password = it }, stringResource(R.string.auth_password), password = true, enabled = !ui.busy)
        Text(stringResource(R.string.auth_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppField(confirm, { confirm = it }, stringResource(R.string.auth_confirm_password), password = true, imeAction = ImeAction.Done,
            onDone = { vm.signUp(name, email, password, confirm) }, enabled = !ui.busy)
        PrimaryButton(stringResource(R.string.auth_sign_up), ui.busy) { vm.signUp(name, email, password, confirm) }
        OrDivider()
        GoogleButton(vm, ui.busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auth_have_account), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onSignIn) { Text(stringResource(R.string.auth_sign_in)) }
        }
    }
}

@Composable
fun VerifyEmailScreen(vm: AuthViewModel, onBack: () -> Unit, onSignIn: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(30) }
    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1000); cooldown-- } }
    AuthScaffold(stringResource(R.string.verify_title), stringResource(R.string.verify_subtitle, ui.pendingEmail.orEmpty()), onBack) {
        Messages(ui)
        AppField(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.verify_code), keyboard = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done, onDone = { vm.verifyEmail(code) }, enabled = !ui.busy)
        Text(stringResource(R.string.verify_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.verify_action), ui.busy) { vm.verifyEmail(code) }
        OutlinedButton({ vm.resendVerification(); cooldown = 30 }, Modifier.fillMaxWidth().height(52.dp), enabled = cooldown == 0 && !ui.busy, shape = MaterialTheme.shapes.medium) {
            Text(if (cooldown > 0) stringResource(R.string.verify_resend_in, cooldown) else stringResource(R.string.verify_resend))
        }
        TextButton(onSignIn, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.verify_already)) }
    }
}

@Composable
fun ForgotPasswordScreen(vm: AuthViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearMessages() }
    AuthScaffold(stringResource(R.string.forgot_title), stringResource(R.string.forgot_subtitle), onBack) {
        Messages(ui)
        AppField(email, { email = it }, stringResource(R.string.auth_email), keyboard = KeyboardType.Email, imeAction = ImeAction.Done,
            onDone = { vm.sendReset(email) }, enabled = !ui.busy)
        PrimaryButton(stringResource(R.string.forgot_action), ui.busy) { vm.sendReset(email) }
    }
}

@Composable
fun ResetCodeScreen(vm: AuthViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    AuthScaffold(stringResource(R.string.reset_code_title), stringResource(R.string.reset_code_subtitle, ui.resetEmail.orEmpty()), onBack) {
        Messages(ui)
        AppField(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.verify_code), keyboard = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done, onDone = { vm.verifyResetCode(code) }, enabled = !ui.busy)
        Text(stringResource(R.string.verify_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.verify_action), ui.busy) { vm.verifyResetCode(code) }
        TextButton({ vm.resendReset() }, Modifier.align(Alignment.CenterHorizontally), enabled = !ui.busy) { Text(stringResource(R.string.verify_resend)) }
    }
}

/** Shown after a reset code or reset link has been accepted. */
@Composable
fun ResetPasswordScreen(vm: AuthViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    AuthScaffold(stringResource(R.string.reset_title), stringResource(R.string.reset_subtitle), null) {
        Messages(ui)
        AppField(password, { password = it }, stringResource(R.string.auth_new_password), password = true, enabled = !ui.busy)
        Text(stringResource(R.string.auth_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppField(confirm, { confirm = it }, stringResource(R.string.auth_confirm_password), password = true, imeAction = ImeAction.Done,
            onDone = { vm.setNewPassword(password, confirm) }, enabled = !ui.busy)
        PrimaryButton(stringResource(R.string.reset_action), ui.busy) { vm.setNewPassword(password, confirm) }
        TextButton({ vm.cancelRecovery() }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
fun AuthNavHost(vm: AuthViewModel) {
    val nav = rememberNavController()
    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                AuthEvent.GO_VERIFY -> nav.navigate("verify")
                AuthEvent.GO_RESET_CODE -> nav.navigate("resetcode")
            }
        }
    }
    AppNavHost(nav, "welcome") {
        composable("welcome") { WelcomeScreen(vm, { nav.navigate("signin") }, { nav.navigate("signup") }) }
        composable("signin") { SignInScreen(vm, { nav.popBackStack() }, { nav.navigate("forgot") }, { nav.navigate("signup") { popUpTo("signin") { inclusive = true } } }) }
        composable("signup") { SignUpScreen(vm, { nav.popBackStack() }, { nav.navigate("signin") { popUpTo("signup") { inclusive = true } } }) }
        composable("verify") { VerifyEmailScreen(vm, { nav.popBackStack() }, { nav.navigate("signin") { popUpTo("welcome") } }) }
        composable("forgot") { ForgotPasswordScreen(vm) { nav.popBackStack() } }
        composable("resetcode") { ResetCodeScreen(vm) { nav.popBackStack() } }
    }
}
