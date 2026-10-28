package org.evsyukov.hermes.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerEditScreen(container: AppContainer, id: String?, onBack: (() -> Unit)?, onSaved: () -> Unit, draft: ServerProfile? = null) {
    val servers by container.servers.state.collectAsState()
    val existing = servers.servers.firstOrNull { it.id == id }
    // A server from a link fills the form; the user still checks and saves it.
    val source = draft ?: existing
    var name by remember(source) { mutableStateOf(source?.name ?: "Hermes") }
    var url by remember(source) { mutableStateOf(source?.baseUrl ?: "") }
    var proxyType by remember(source) { mutableStateOf(source?.proxy?.type ?: ProxyType.NONE) }
    var proxyHost by remember(source) { mutableStateOf(source?.proxy?.host ?: "") }
    var proxyPort by remember(source) { mutableStateOf(source?.proxy?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var proxyUser by remember(source) { mutableStateOf(source?.proxy?.username ?: "") }
    var proxyPassword by remember(source) { mutableStateOf(source?.proxy?.password ?: "") }
    var controlUrl by remember(source) { mutableStateOf(source?.tailnet?.controlUrl ?: "") }
    var authKey by remember(source) { mutableStateOf(source?.tailnet?.authKey ?: "") }
    var hostname by remember(source) { mutableStateOf(source?.tailnet?.hostname ?: "hermes-android") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tailnetStatus by container.tailnet.status.collectAsState()

    fun profile() = ServerProfile(
        id = existing?.id ?: draft?.id ?: UUID.randomUUID().toString(),
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
                title = { Text(stringResource(if (existing == null) R.string.setup_new_server else R.string.setup_server), fontSize = 18.sp) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                },
                actions = {
                    if (existing != null && servers.servers.size > 1) {
                        IconButton(onClick = { scope.launch { container.servers.remove(existing.id); onBack?.invoke() } }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.setup_delete_server))
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
                Text(stringResource(R.string.setup_title), fontSize = 26.sp, color = ChatTheme.colors.text)
                Text(
                    stringResource(R.string.setup_intro),
                    color = ChatTheme.colors.secondaryText,
                )
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.setup_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it },
                label = { Text(stringResource(R.string.setup_address)) },
                placeholder = { Text("https://hermes.example.org") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.setup_route), color = ChatTheme.colors.secondaryText, fontSize = 14.sp)
            // Chips wrap: with a large system font four of them do not fit one line.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ProxyType.NONE to stringResource(R.string.setup_route_direct), ProxyType.TAILSCALE to "Tailscale", ProxyType.SOCKS5 to "SOCKS5", ProxyType.HTTP to "HTTP").forEach { (type, label) ->
                    FilterChip(selected = proxyType == type, onClick = { proxyType = type }, label = { Text(label) })
                }
            }
            when (proxyType) {
                ProxyType.NONE -> Text(
                    stringResource(R.string.setup_route_direct_hint),
                    color = ChatTheme.colors.faintText, fontSize = 13.sp,
                )
                ProxyType.HTTP, ProxyType.SOCKS5 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text(stringResource(R.string.setup_proxy_host)) }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(
                            proxyPort, { proxyPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.setup_port)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(proxyUser, { proxyUser = it }, label = { Text(stringResource(R.string.setup_login)) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            proxyPassword, { proxyPassword = it }, label = { Text(stringResource(R.string.setup_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
                        )
                    }
                    Text(stringResource(R.string.setup_proxy_hint), color = ChatTheme.colors.faintText, fontSize = 13.sp)
                }
                ProxyType.TAILSCALE -> {
                    if (!tailnetStatus.available) {
                        Text(
                            stringResource(R.string.setup_tailscale_missing),
                            color = ChatTheme.colors.danger, fontSize = 13.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.setup_tailscale_hint),
                        color = ChatTheme.colors.faintText, fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        controlUrl, { controlUrl = it }, label = { Text(stringResource(R.string.setup_control)) },
                        placeholder = { Text(stringResource(R.string.setup_control_placeholder)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        authKey, { authKey = it }, label = { Text(stringResource(R.string.setup_auth_key)) },
                        placeholder = { Text(stringResource(R.string.setup_auth_key_placeholder)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(hostname, { hostname = it }, label = { Text(stringResource(R.string.setup_node_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.setup_node_state, tailnetStatus.state) + (tailnetStatus.ips.firstOrNull()?.let { " · $it" } ?: "") + (tailnetStatus.error?.let { " · $it" } ?: ""),
                        color = ChatTheme.colors.secondaryText, fontSize = 13.sp,
                    )
                    tailnetStatus.authUrl?.takeIf { !tailnetStatus.running }?.let { link ->
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) {
                            Text(stringResource(R.string.setup_tailnet_sign_in))
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
                    if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.setup_check))
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
                ) { Text(stringResource(R.string.common_save)) }
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
    val session = ServerSession(profile, clientFor(baseClient(), profile.proxy, tailnetProxy), container.servers, container.strings)
    val info = session.authInfo()
    buildString {
        append(container.strings.get(R.string.setup_check_ok))
        info.version?.let { append(container.strings.get(R.string.setup_check_version, it)) }
        append(". ")
        append(
            when {
                !info.required -> container.strings.get(R.string.setup_check_no_auth)
                info.nativePkce -> container.strings.get(R.string.setup_check_auth_via, info.providers.joinToString().ifEmpty { container.strings.get(R.string.setup_check_browser) })
                else -> container.strings.get(R.string.setup_check_unsupported)
            },
        )
    }
} catch (e: Exception) {
    container.strings.get(R.string.setup_check_failed, e.message ?: e.javaClass.simpleName)
}
