package org.evsyukov.hermes.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.BuildConfig
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.admin.AdminScaffold
import org.evsyukov.hermes.ui.admin.ClickRow
import org.evsyukov.hermes.ui.admin.SectionTitle
import org.evsyukov.hermes.ui.theme.ChatTheme
import org.evsyukov.hermes.ui.theme.ThemeMode
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onSignOut: () -> Unit) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.current.collectAsState()
    val tailnet by container.tailnet.status.collectAsState()
    var cacheSize by remember { mutableLongStateOf(container.attachments.size()) }
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
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_check_every), color = colors.secondaryText, fontSize = 14.sp)
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
            ClickRow(stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_hint), onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            })

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
            ClickRow(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_megabytes, cacheSize / 1_048_576.0), onClick = {
                container.attachments.clear()
                cacheSize = container.attachments.size()
            })
            ClickRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint), onClick = onSignOut)

            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.size(32.dp))
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

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ClickRow(title, sub, onClick = { onChange(!value) }) { Switch(value, onChange) }
}
