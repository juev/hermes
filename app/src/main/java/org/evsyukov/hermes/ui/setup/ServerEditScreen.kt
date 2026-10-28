package org.evsyukov.hermes.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.TailnetSettings
import org.evsyukov.hermes.data.server.baseClient
import org.evsyukov.hermes.data.server.clientFor
import org.evsyukov.hermes.data.server.normalizeBaseUrl
import org.evsyukov.hermes.ui.theme.ChatTheme

/** Adds or edits a server; the first run of the app starts here with [onBack] null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(container: AppContainer, id: String?, onBack: (() -> Unit)?, onSaved: () -> Unit) {
    val existing = container.servers.state.value.servers.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(existing?.name ?: "Hermes") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var proxyType by remember { mutableStateOf(existing?.proxy?.type ?: ProxyType.NONE) }
    var proxyHost by remember { mutableStateOf(existing?.proxy?.host ?: "") }
    var proxyPort by remember { mutableStateOf(existing?.proxy?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var proxyUser by remember { mutableStateOf(existing?.proxy?.username ?: "") }
    var proxyPassword by remember { mutableStateOf(existing?.proxy?.password ?: "") }
    var controlUrl by remember { mutableStateOf(existing?.tailnet?.controlUrl ?: "") }
    var authKey by remember { mutableStateOf(existing?.tailnet?.authKey ?: "") }
    var hostname by remember { mutableStateOf(existing?.tailnet?.hostname ?: "hermes-android") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tailnetStatus by container.tailnet.status.collectAsState()

    fun profile() = ServerProfile(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { "Hermes" },
        baseUrl = normalizeBaseUrl(url) ?: url.trim(),
        proxy = ProxySettings(proxyType, proxyHost.trim(), proxyPort.toIntOrNull() ?: 0, proxyUser, proxyPassword),
        tailnet = TailnetSettings(controlUrl.trim().trimEnd('/'), authKey.trim(), hostname.trim()),
    )

    val valid = normalizeBaseUrl(url) != null &&
        (proxyType != ProxyType.HTTP && proxyType != ProxyType.SOCKS5 || proxyHost.isNotBlank() && (proxyPort.toIntOrNull() ?: 0) in 1..65535)

    Scaffold(
        containerColor = ChatTheme.colors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Новый сервер" else "Сервер", fontSize = 18.sp) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (existing != null && container.servers.state.value.servers.size > 1) {
                        IconButton(onClick = { scope.launch { container.servers.remove(existing.id); onBack?.invoke() } }) {
                            Icon(Icons.Outlined.Delete, "Удалить сервер")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatTheme.colors.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack == null) {
                Text("Подключение к Hermes", fontSize = 26.sp, color = ChatTheme.colors.text)
                Text(
                    "Адрес веб-панели Hermes (hermes dashboard). Вход идёт через её же провайдера: Pocket ID, OAuth или пароль.",
                    color = ChatTheme.colors.secondaryText,
                )
            }
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it },
                label = { Text("Адрес") },
                placeholder = { Text("https://hermes.example.org") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Как добраться до сервера", color = ChatTheme.colors.secondaryText, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ProxyType.NONE to "Напрямую", ProxyType.TAILSCALE to "Tailscale", ProxyType.SOCKS5 to "SOCKS5", ProxyType.HTTP to "HTTP").forEach { (type, label) ->
                    FilterChip(selected = proxyType == type, onClick = { proxyType = type }, label = { Text(label) })
                }
            }
            when (proxyType) {
                ProxyType.NONE -> Text(
                    "Подходит, если сервер открыт в интернет или телефон уже в частной сети: приложение Tailscale, WireGuard, домашний Wi-Fi.",
                    color = ChatTheme.colors.faintText, fontSize = 13.sp,
                )
                ProxyType.HTTP, ProxyType.SOCKS5 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text("Хост прокси") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(
                            proxyPort, { proxyPort = it.filter(Char::isDigit) }, label = { Text("Порт") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(proxyUser, { proxyUser = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            proxyPassword, { proxyPassword = it }, label = { Text("Пароль") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
                        )
                    }
                    Text("Имя сервера разрешает сам прокси, так что подходят и имена частной сети.", color = ChatTheme.colors.faintText, fontSize = 13.sp)
                }
                ProxyType.TAILSCALE -> {
                    if (!tailnetStatus.available) {
                        Text(
                            "Встроенный Tailscale не вошёл в эту сборку (нет app/libs/tailnet.aar). Соберите его scripts/build-tailnet.sh.",
                            color = ChatTheme.colors.danger, fontSize = 13.sp,
                        )
                    }
                    Text(
                        "Приложение само входит в tailnet и ходит к серверу через свой узел. VPN на телефоне не нужен, другой VPN продолжает работать.",
                        color = ChatTheme.colors.faintText, fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        controlUrl, { controlUrl = it }, label = { Text("Сервер управления") },
                        placeholder = { Text("пусто — Tailscale, или https://headscale.example.org") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        authKey, { authKey = it }, label = { Text("Ключ авторизации (необязательно)") },
                        placeholder = { Text("tskey-… или preauth key Headscale") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(hostname, { hostname = it }, label = { Text("Имя узла") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Узел: ${tailnetStatus.state}" + (tailnetStatus.ips.firstOrNull()?.let { " · $it" } ?: "") + (tailnetStatus.error?.let { " · $it" } ?: ""),
                        color = ChatTheme.colors.secondaryText, fontSize = 13.sp,
                    )
                    tailnetStatus.authUrl?.takeIf { !tailnetStatus.running }?.let { link ->
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) {
                            Text("Войти в tailnet")
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = valid && !checking,
                    onClick = {
                        checking = true
                        result = null
                        scope.launch {
                            result = check(container, profile())
                            checking = false
                        }
                    },
                ) {
                    if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Проверить")
                }
                Button(
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = ChatTheme.colors.contrast, contentColor = ChatTheme.colors.onContrast),
                    onClick = {
                        scope.launch {
                            container.servers.save(profile())
                            onSaved()
                        }
                    },
                ) { Text("Сохранить") }
            }
            result?.let { Text(it, color = ChatTheme.colors.secondaryText) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Asks `/api/status` through the chosen route and says what came back, in words. */
private suspend fun check(container: AppContainer, profile: ServerProfile): String = try {
    val tailnetProxy = if (profile.proxy.type == ProxyType.TAILSCALE) {
        container.tailnet.ensure(profile.tailnet)
        withTimeout(60_000) { container.tailnet.proxy.filterNotNull().first() }
    } else {
        null
    }
    val session = ServerSession(profile, clientFor(baseClient(), profile.proxy, tailnetProxy), container.servers)
    val info = session.authInfo()
    buildString {
        append("Сервер отвечает")
        info.version?.let { append(", Hermes $it") }
        append(". ")
        append(
            when {
                !info.required -> "Вход не требуется."
                info.nativePkce -> "Вход через ${info.providers.joinToString().ifEmpty { "браузер" }}."
                else -> "Сервер не поддерживает вход для приложений (native_pkce)."
            },
        )
    }
} catch (e: Exception) {
    "Не отвечает: ${e.message ?: e.javaClass.simpleName}"
}
