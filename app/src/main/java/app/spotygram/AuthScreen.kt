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
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Назад") }
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
            if (welcome) "Твоя музыка. Из твоих чатов."
            else
                when (auth.type) {
                    "authorizationStateWaitPhoneNumber" -> "Вход в Telegram"
                    "authorizationStateWaitCode" -> "Код подтверждения"
                    "authorizationStateWaitPassword" -> "Пароль Telegram"
                    "authorizationStateWaitEmailAddress" -> "Электронная почта"
                    "authorizationStateWaitEmailCode" -> "Код из письма"
                    "authorizationStateWaitOtherDeviceConfirmation" -> "Подтвердите вход в Telegram"
                    else -> "Подключение"
                },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        if (welcome) {
            Text(
                "Собери аудиофайлы из любимых чатов в одну медиатеку. Слушай с выключенным экраном и сохраняй на телефон.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = { app.telegram.start() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("Подключить Telegram")
            }
            TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Пока без Telegram")
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
                    "authorizationStateWaitPhoneNumber" ->
                        "Введите номер своего аккаунта с кодом страны."
                    "authorizationStateWaitCode" ->
                        if (auth.detail.contains("TelegramMessage"))
                            "Код отправлен в приложение Telegram."
                        else "Введите код, отправленный способом, выбранным Telegram."
                    "authorizationStateWaitPassword" ->
                        "Ваш пароль двухэтапной аутентификации." +
                            if (auth.detail.isNotBlank()) " Подсказка: ${auth.detail}" else ""
                    "authorizationStateWaitEmailCode" -> "Код отправлен на ${auth.detail}"
                    "authorizationStateWaitRegistration" ->
                        "Сначала создайте аккаунт в официальном Telegram, затем войдите здесь."
                    "authorizationStateWaitPremiumPurchase" ->
                        "Telegram требует оплату для этого способа входа. Попробуйте сначала войти в официальном приложении."
                    else -> "Дождитесь ответа Telegram."
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
                            if (phone) "Номер телефона"
                            else if (password) "Пароль"
                            else if (auth.type == "authorizationStateWaitEmailAddress") "Email"
                            else "Код"
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
                                        "Показать пароль",
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
                    else Text("Продолжить")
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
                        Text("Отправить код ещё раз")
                    }
            } else if (auth.busy || auth.type == "starting")
                CircularProgressIndicator(Modifier.padding(16.dp))
            if (auth.error.isNotBlank())
                Text(
                    auth.error,
                    Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            TextButton(onClick = onLocal) { Text("Перейти к локальной музыке") }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Неофициальное приложение на Telegram API. Вход создаёт сессию с доступом к аккаунту; музыку добавляем только из выбранных чатов. Сессия хранится на этом устройстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
