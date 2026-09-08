package app.spotygram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AuthScreen(app: SpotygramApp, auth: AuthState, onLocal: () -> Unit, onBack: () -> Unit) {
    var value by rememberSaveable(auth.type) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, tr(R.string.back)) }
        }
        Spacer(Modifier.height(44.dp))
        Icon(
            Icons.Rounded.GraphicEq,
            null,
            Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text("Spotygram", fontSize = 40.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        val welcome = auth.type in listOf("welcome", "authorizationStateClosed")
        Text(
            if (welcome) tr(R.string.welcome_tagline)
            else
                when (auth.type) {
                    "authorizationStateWaitPhoneNumber" -> tr(R.string.telegram_login)
                    "authorizationStateWaitCode" -> tr(R.string.verification_code)
                    "authorizationStateWaitPassword" -> tr(R.string.telegram_password)
                    "authorizationStateWaitEmailAddress" -> tr(R.string.email_address)
                    "authorizationStateWaitEmailCode" -> tr(R.string.email_code)
                    "authorizationStateWaitOtherDeviceConfirmation" ->
                        tr(R.string.confirm_telegram_login)
                    else -> tr(R.string.connecting)
                },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        if (welcome) {
            Text(
                tr(R.string.welcome_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = { app.telegram.start() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(tr(R.string.connect_telegram))
            }
            TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tr(R.string.continue_without_telegram))
            }
        } else {
            val supported =
                auth.type in
                    listOf(
                        "authorizationStateWaitPhoneNumber",
                        "authorizationStateWaitCode",
                        "authorizationStateWaitPassword",
                        "authorizationStateWaitEmailAddress",
                        "authorizationStateWaitEmailCode",
                    )
            val phone = auth.type == "authorizationStateWaitPhoneNumber"
            val password = auth.type == "authorizationStateWaitPassword"
            Text(
                when (auth.type) {
                    "authorizationStateWaitPhoneNumber" -> tr(R.string.enter_phone_help)
                    "authorizationStateWaitCode" ->
                        if (auth.detail.contains("TelegramMessage")) tr(R.string.code_sent_telegram)
                        else tr(R.string.enter_code_help)
                    "authorizationStateWaitPassword" ->
                        tr(R.string.password_help) +
                            if (auth.detail.isNotBlank()) tr(R.string.password_hint, auth.detail)
                            else ""
                    "authorizationStateWaitEmailCode" -> tr(R.string.email_code_sent, auth.detail)
                    "authorizationStateWaitRegistration" -> tr(R.string.registration_help)
                    "authorizationStateWaitPremiumPurchase" -> tr(R.string.auth_payment_help)
                    else -> tr(R.string.wait_telegram)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            if (supported) {
                OutlinedTextField(
                    value,
                    { value = it },
                    label = {
                        Text(
                            if (phone) tr(R.string.phone_number)
                            else if (password) tr(R.string.password)
                            else if (auth.type == "authorizationStateWaitEmailAddress") "Email"
                            else tr(R.string.code)
                        )
                    },
                    singleLine = true,
                    enabled = !auth.busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                if (phone) KeyboardType.Phone
                                else if (password) KeyboardType.Password
                                else if (auth.type.contains("EmailAddress")) KeyboardType.Email
                                else KeyboardType.Number
                        ),
                    visualTransformation =
                        if (password && !passwordVisible) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    trailingIcon =
                        if (password) {
                            {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Rounded.VisibilityOff
                                        else Icons.Rounded.Visibility,
                                        tr(R.string.show_password),
                                    )
                                }
                            }
                        } else null,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { app.telegram.submit(value) },
                    enabled = value.isNotBlank() && !auth.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (auth.busy)
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(tr(R.string.continue_action))
                }
                if (auth.type == "authorizationStateWaitCode")
                    TextButton(
                        onClick = {
                            app.action {
                                app.telegram.request(
                                    json("resendAuthenticationCode", "reason" to null)
                                )
                            }
                        }
                    ) {
                        Text(tr(R.string.resend_code))
                    }
            } else if (auth.busy || auth.type == "starting")
                CircularProgressIndicator(Modifier.padding(16.dp))
            if (auth.error.isNotBlank())
                Text(
                    auth.error,
                    Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            TextButton(onClick = onLocal) { Text(tr(R.string.open_local_music)) }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            tr(R.string.auth_privacy_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
