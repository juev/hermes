package org.evsyukov.hermes.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.BuildConfig
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.data.settings.cacheLimitOf
import org.evsyukov.hermes.push.PushState
import org.evsyukov.hermes.ui.admin.AdminScaffold
import org.evsyukov.hermes.ui.admin.ClickRow
import org.evsyukov.hermes.ui.admin.SectionTitle
import org.evsyukov.hermes.ui.theme.ChatTheme
import org.evsyukov.hermes.ui.theme.ThemeMode
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onLicenses: () -> Unit, onSignOut: () -> Unit) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    val settings by container.settings.current.collectAsState()
    val tailnet by container.tailnet.status.collectAsState()
    var cacheSize by remember { mutableLongStateOf(container.attachments.size()) }
    var cacheTick by remember { mutableIntStateOf(0) }
    var editLimit by remember { mutableStateOf(false) }
    LaunchedEffect(cacheTick, settings.cacheLimitMb) {
        cacheSize = container.attachments.size() + runCatching { container.responses.size() }.getOrDefault(0)
    }
    var tailnetLogs by remember { mutableStateOf<String?>(null) }

    fun change(update: (Settings) -> Settings) = scope.launch { container.settings.update(update) }

    AdminScaffold(stringResource(R.string.common_settings), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_appearance))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                    ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                ).forEach { (mode, label) ->
                    FilterChip(settings.theme == mode, { change { it.copy(theme = mode) } }, label = { Text(label) })
                }
            }

            SectionTitle(stringResource(R.string.settings_chat))
            Toggle(stringResource(R.string.settings_enter_sends), stringResource(R.string.settings_enter_sends_hint), settings.enterSends) { v -> change { it.copy(enterSends = v) } }
            Toggle(stringResource(R.string.settings_pull), stringResource(R.string.settings_pull_hint), settings.pullForNewChat) { v -> change { it.copy(pullForNewChat = v) } }
            Toggle(stringResource(R.string.settings_reasoning), stringResource(R.string.settings_reasoning_hint), settings.showReasoning) { v -> change { it.copy(showReasoning = v) } }
            Toggle(stringResource(R.string.settings_haptics), null, settings.haptics) { v -> change { it.copy(haptics = v) } }

            SectionTitle(stringResource(R.string.settings_background))
            Text(
                stringResource(R.string.settings_background_hint),
                color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Toggle(stringResource(R.string.settings_activity), stringResource(R.string.settings_activity_hint), settings.notifyActivity) { v -> change { it.copy(notifyActivity = v) } }
            if (settings.notifyActivity) {
                // The label sits above the chips: beside them the row does not fit a phone in Russian.
                Text(stringResource(R.string.settings_check_every), color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { m -> FilterChip(settings.checkMinutes == m, { change { it.copy(checkMinutes = m) } }, label = { Text(stringResource(R.string.settings_minutes, m)) }) }
                }
                Toggle(
                    stringResource(R.string.settings_keep),
                    stringResource(R.string.settings_keep_hint),
                    settings.keepConnected,
                ) { v -> change { it.copy(keepConnected = v) } }
            }
            ClickRow(stringResource(R.string.settings_system_notifications), stringResource(R.string.settings_system_notifications_hint), onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
            })
            BatteryRow(settings.keepConnected)

            SectionTitle(stringResource(R.string.settings_push))
            val push by container.push.status.collectAsState()
            Toggle(stringResource(R.string.settings_push_switch), stringResource(R.string.settings_push_switch_hint), settings.push) { v -> change { it.copy(push = v) } }
            val pushApp = push.distributors.firstOrNull { it.packageName == push.distributor }?.label ?: push.distributor.orEmpty()
            Text(
                when (push.state) {
                    PushState.OFF -> stringResource(R.string.push_state_off)
                    PushState.NO_DISTRIBUTOR -> stringResource(R.string.push_state_no_distributor)
                    PushState.CHOOSE -> stringResource(R.string.push_state_choose)
                    PushState.CONNECTING -> stringResource(R.string.push_state_connecting)
                    PushState.ON -> stringResource(R.string.push_state_on, pushApp)
                    PushState.UNSUPPORTED -> stringResource(R.string.push_state_unsupported)
                    PushState.FAILED -> stringResource(R.string.push_state_failed)
                },
                color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (push.state == PushState.CHOOSE) {
                push.distributors.forEach { d -> ClickRow(d.label, d.packageName, onClick = { scope.launch { container.push.choose(d.packageName) } }) }
            }
            if (push.state == PushState.ON) {
                ClickRow(stringResource(R.string.push_test), null, onClick = {
                    scope.launch {
                        val text = try {
                            resources.getString(R.string.push_test_sent, container.push.test())
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                })
            }

            SectionTitle(stringResource(R.string.settings_tailscale))
            if (!tailnet.available) {
                Text(stringResource(R.string.settings_tailscale_absent), color = colors.faintText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.settings_tailnet_state, tailnet.state), color = colors.text)
                    tailnet.name?.let { Text(it, color = colors.secondaryText, fontSize = 13.sp) }
                    if (tailnet.ips.isNotEmpty()) Text(tailnet.ips.joinToString(), color = colors.secondaryText, fontSize = 13.sp)
                    tailnet.error?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                }
                ClickRow(stringResource(R.string.settings_node_log), null, onClick = { tailnetLogs = container.tailnet.logs() })
                ClickRow(stringResource(R.string.settings_tailnet_leave), stringResource(R.string.settings_tailnet_leave_hint), onClick = { container.tailnet.reset() })
            }

            SectionTitle(stringResource(R.string.settings_session))
            val servers by container.servers.state.collectAsState()
            val tokens = servers.active?.let { servers.tokens[it.id] }
            Text(
                when {
                    tokens == null -> stringResource(R.string.settings_session_none)
                    tokens.expiresAt == 0L -> stringResource(R.string.settings_session_forever)
                    else -> stringResource(
                        if (tokens.refreshToken.isNotEmpty()) R.string.settings_token_renews else R.string.settings_token_expires,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tokens.expiresAt * 1000)),
                    )
                },
                color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle(stringResource(R.string.settings_data))
            ClickRow(stringResource(R.string.settings_cache_limit), stringResource(R.string.settings_cache_limit_hint, settings.cacheLimitMb), onClick = { editLimit = true })
            ClickRow(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_megabytes, cacheSize / 1_048_576.0), onClick = {
                scope.launch {
                    container.attachments.clear()
                    runCatching { container.responses.clear() }
                    cacheTick++
                }
            })
            ClickRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint), onClick = onSignOut)

            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            // No app may be able to open the address; the screen stays as it is then.
            ClickRow(stringResource(R.string.settings_source), SOURCE_URL, onClick = { runCatching { browser.openUri(SOURCE_URL) } })
            ClickRow(stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_hint), onClick = onLicenses)
            Spacer(Modifier.size(32.dp))
        }
    }
    if (editLimit) {
        CacheLimitDialog(settings.cacheLimitMb, onDismiss = { editLimit = false }) { mb ->
            editLimit = false
            change { it.copy(cacheLimitMb = mb) }
        }
    }
    tailnetLogs?.let { logs ->
        AlertDialog(
            onDismissRequest = { tailnetLogs = null },
            title = { Text(stringResource(R.string.settings_tailscale_log)) },
            text = {
                SelectionContainer {
                    Text(
                        logs.ifBlank { stringResource(R.string.settings_empty) }, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { tailnetLogs = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

/** Asks for the limit of the cache in megabytes; any whole number from zero up is accepted. */
@Composable
private fun CacheLimitDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = cacheLimitOf(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cache_limit)) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, isError = parsed == null,
                label = { Text(stringResource(R.string.settings_cache_limit_field)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Battery optimization of the app as the system has it. Only the permanent connection needs it off,
 * so the row shows while that is on, or while it is off already and can be turned back on. The tap
 * opens the system list, where it changes both ways; the state is read again when the user returns.
 */
@Composable
internal fun BatteryRow(keepConnected: Boolean) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(batteryExempt(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = batteryExempt(context)
        onPauseOrDispose {}
    }
    if (!keepConnected && !exempt) return
    ClickRow(
        stringResource(R.string.settings_battery),
        stringResource(if (exempt) R.string.settings_battery_off else R.string.settings_battery_on),
        onClick = {
            try {
                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        },
    )
}

private fun batteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ClickRow(title, sub, onClick = { onChange(!value) }) { Switch(value, onChange) }
}

private const val SOURCE_URL = "https://github.com/juev/hermes"
