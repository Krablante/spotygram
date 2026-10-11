package app.spotygram

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthScreen(app: SpotygramApp, auth: AuthState, onLocal: () -> Unit, onBack: () -> Unit) {
    var value by rememberSaveable(auth.type, auth.editingPhone) {
        mutableStateOf(if (auth.editingPhone) auth.phoneNumber else "")
    }
    var passwordVisible by remember { mutableStateOf(false) }
    val phone = auth.type == "authorizationStateWaitPhoneNumber" || auth.editingPhone
    val waitingCode = !phone && auth.type == "authorizationStateWaitCode"
    var resendSeconds by remember(auth.resendAt) { mutableIntStateOf(auth.resendSeconds()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(auth.resendAt, waitingCode, lifecycle) {
        if (waitingCode) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            resendSeconds = auth.resendSeconds()
            while (resendSeconds > 0) {
                delay(1000)
                resendSeconds = auth.resendSeconds()
            }
        }
    }
    val keyboardVisible = imeVisible()
    val back = {
        if (!auth.busy) {
            if (auth.canChangePhone && !phone)
                app.action { app.telegram.usePhoneAuthentication() }
            else onBack()
        }
        Unit
    }
    BackHandler { back() }
    Column(
        Modifier.widthIn(max = 480.dp)
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            GlassIconButton(onClick = back, enabled = !auth.busy) {
                Icon(Icons.Rounded.ArrowBack, tr(R.string.back))
            }
        }
        if (!keyboardVisible) {
            Spacer(Modifier.height(20.dp))
            if (!phone && auth.canChangePhone) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.spotygram_mark), null, Modifier.size(40.dp))
                    Text("Spotygram", style = MaterialTheme.typography.headlineSmall)
                }
            } else {
                Image(painterResource(R.drawable.spotygram_mark), null, Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Spotygram", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
        val welcome = auth.type in listOf("welcome", "authorizationStateClosed")
        Text(
            if (welcome) tr(R.string.welcome_tagline)
            else if (phone) tr(R.string.telegram_login)
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
            Spacer(Modifier.height(24.dp))
            GlassButton(
                onClick = { app.telegram.start() },
                modifier = Modifier.fillMaxWidth(),
                tint = spectrumAccent(0),
            ) {
                Text(tr(R.string.connect_telegram))
            }
            TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tr(R.string.continue_without_telegram))
            }
        } else {
            val supported = phone ||
                auth.type in
                    listOf(
                        "authorizationStateWaitPhoneNumber",
                        "authorizationStateWaitCode",
                        "authorizationStateWaitPassword",
                        "authorizationStateWaitEmailAddress",
                        "authorizationStateWaitEmailCode",
                    )
            val password = !phone && auth.type == "authorizationStateWaitPassword"
            Text(
                if (phone) tr(R.string.enter_phone_help) else when (auth.type) {
                    "authorizationStateWaitPhoneNumber" -> tr(R.string.enter_phone_help)
                    "authorizationStateWaitCode" -> when (auth.detail) {
                        "authenticationCodeTypeTelegramMessage" -> tr(R.string.code_help_telegram)
                        "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord",
                        "authenticationCodeTypeSmsPhrase" -> tr(R.string.code_help_sms)
                        "authenticationCodeTypeCall" -> tr(R.string.code_help_call)
                        "authenticationCodeTypeMissedCall" -> tr(R.string.code_help_missed_call, auth.codeLength)
                        "authenticationCodeTypeFragment" -> tr(R.string.code_help_fragment)
                        else -> tr(R.string.enter_code_help)
                    }
                    "authorizationStateWaitPassword" ->
                        tr(R.string.password_help) +
                            if (auth.detail.isNotBlank()) tr(R.string.password_hint, auth.detail)
                            else ""
                    "authorizationStateWaitEmailCode" -> tr(R.string.email_code_sent, auth.detail)
                    "authorizationStateWaitRegistration" -> tr(R.string.registration_help)
                    "authorizationStateWaitPremiumPurchase" -> tr(R.string.auth_payment_help)
                    "authorizationStateWaitOtherDeviceConfirmation" -> tr(R.string.qr_login_help)
                    else -> tr(R.string.wait_telegram)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (waitingCode && auth.phoneNumber.isNotBlank()) {
                Text(
                    auth.phoneNumber,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (waitingCode && auth.codeLink.startsWith("https://fragment.com/")) {
                TextButton(onClick = { app.platform.openUrl(auth.codeLink) }) {
                    Text(tr(R.string.open_fragment))
                }
            }
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
                    modifier = Modifier.fillMaxWidth().liquidGlass(20.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                if (phone) KeyboardType.Phone
                                else if (password) KeyboardType.Password
                                else if (auth.type.contains("EmailAddress")) KeyboardType.Email
                                else if (auth.detail in listOf("authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase")) KeyboardType.Text
                                else KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (value.isNotBlank() && !auth.busy) app.telegram.submit(value, auth)
                    }),
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
                GlassButton(
                    onClick = { app.telegram.submit(value, auth) },
                    enabled = value.isNotBlank() && !auth.busy,
                    modifier = Modifier.fillMaxWidth(),
                    tint = spectrumAccent(0),
                ) {
                    if (auth.busy)
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(tr(R.string.continue_action))
                }
                if (waitingCode && auth.nextCodeType.isNotEmpty())
                    TextButton(
                        onClick = { app.telegram.resendCode() },
                        enabled = !auth.busy && resendSeconds == 0,
                        colors = ButtonDefaults.textButtonColors(
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(if (resendSeconds > 0) tr(R.string.resend_code_in, resendSeconds)
                            else when (auth.nextCodeType) {
                                "authenticationCodeTypeSms" -> tr(R.string.resend_code_sms)
                                "authenticationCodeTypeCall" -> tr(R.string.resend_code_call)
                                "authenticationCodeTypeMissedCall" -> tr(R.string.resend_code_call)
                                else -> tr(R.string.resend_code)
                            })
                    }
                if (waitingCode && auth.nextCodeType.isEmpty()) {
                    Text(
                        tr(R.string.code_resend_unavailable),
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (phone || waitingCode) TextButton(
                    onClick = { app.telegram.useQrAuthentication() },
                    enabled = !auth.busy,
                ) { Text(tr(R.string.qr_login)) }
            } else if (auth.type == "authorizationStateWaitOtherDeviceConfirmation" && auth.detail.isNotBlank()) {
                val matrix = remember(auth.detail) {
                    com.google.zxing.qrcode.QRCodeWriter().encode(auth.detail, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
                }
                Canvas(Modifier.size(240.dp).background(Color.White)) {
                    val cell = size.width / matrix.width
                    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                        if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                    }
                }
                TextButton(onClick = { app.action { app.telegram.usePhoneAuthentication() } }, enabled = !auth.busy) {
                    Text(tr(R.string.login_using_phone))
                }
            } else if (auth.busy || auth.type == "starting")
                CircularProgressIndicator(Modifier.padding(16.dp))
            if (auth.canChangePhone && !phone && auth.type != "authorizationStateWaitOtherDeviceConfirmation")
                TextButton(onClick = back, enabled = !auth.busy) {
                    Text(tr(R.string.change_phone_number))
                }
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
