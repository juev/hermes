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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.BuildConfig
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

    AdminScaffold("Настройки", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Оформление")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (mode, label) ->
                    FilterChip(settings.theme == mode, { change { it.copy(theme = mode) } }, label = { Text(label) })
                }
            }

            SectionTitle("Чат")
            Toggle("Enter отправляет", "Иначе Enter переносит строку", settings.enterSends) { v -> change { it.copy(enterSends = v) } }
            Toggle("Потянуть вверх — новый чат", "Жест в конце ленты сообщений", settings.pullForNewChat) { v -> change { it.copy(pullForNewChat = v) } }
            Toggle("Размышления раскрыты", "Показывать рассуждения модели без нажатия", settings.showReasoning) { v -> change { it.copy(showReasoning = v) } }
            Toggle("Вибрация", null, settings.haptics) { v -> change { it.copy(haptics = v) } }

            SectionTitle("Фон и уведомления")
            Text(
                "Пока агент отвечает, приложение держит соединение и после сворачивания, а по готовности присылает уведомление.",
                color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Toggle("Новые события", "Ответы cron, сообщения из Telegram и с других устройств", settings.notifyActivity) { v -> change { it.copy(notifyActivity = v) } }
            if (settings.notifyActivity) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверять каждые", color = colors.secondaryText, fontSize = 14.sp)
                    listOf(15, 30, 60).forEach { m -> FilterChip(settings.checkMinutes == m, { change { it.copy(checkMinutes = m) } }, label = { Text("$m мин") }) }
                }
                Toggle(
                    "Постоянное подключение",
                    "События приходят сразу, но в шторке висит значок и батарея тратится быстрее",
                    settings.keepConnected,
                ) { v -> change { it.copy(keepConnected = v) } }
            }
            ClickRow("Уведомления в системе", "Каналы, звук, показ на экране блокировки", onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
            })
            ClickRow("Без ограничений батареи", "Чтобы система не останавливала фоновую работу", onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            })

            SectionTitle("Встроенный Tailscale")
            if (!tailnet.available) {
                Text("Не входит в эту сборку.", color = colors.faintText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Состояние: ${tailnet.state}", color = colors.text)
                    tailnet.name?.let { Text(it, color = colors.secondaryText, fontSize = 13.sp) }
                    if (tailnet.ips.isNotEmpty()) Text(tailnet.ips.joinToString(), color = colors.secondaryText, fontSize = 13.sp)
                    tailnet.error?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                }
                ClickRow("Журнал узла", null, onClick = { tailnetLogs = container.tailnet.logs() })
                ClickRow("Выйти из tailnet", "Ключ узла удалится, при следующем запуске нужен новый вход", onClick = { container.tailnet.reset() })
            }

            SectionTitle("Сессия")
            val servers by container.servers.state.collectAsState()
            val tokens = servers.active?.let { servers.tokens[it.id] }
            Text(
                when {
                    tokens == null -> "Вход не выполнен или сервер его не требует."
                    tokens.expiresAt == 0L -> "Вход без срока действия."
                    else -> "Токен действует до " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tokens.expiresAt * 1000)) +
                        if (tokens.refreshToken.isNotEmpty()) ", дальше продлится сам." else ", токена обновления нет: потом понадобится новый вход."
                },
                color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle("Данные")
            ClickRow("Очистить кэш вложений", "%.1f МБ".format(cacheSize / 1_048_576.0), onClick = {
                container.attachments.clear()
                cacheSize = container.attachments.size()
            })
            ClickRow("Выйти с сервера", "Токены этого сервера удалятся с телефона", onClick = onSignOut)

            SectionTitle("О приложении")
            Text("Hermes для Android ${BuildConfig.VERSION_NAME}", color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.size(32.dp))
        }
    }
    tailnetLogs?.let { logs ->
        AlertDialog(
            onDismissRequest = { tailnetLogs = null },
            title = { Text("Журнал Tailscale") },
            text = {
                SelectionContainer {
                    Text(
                        logs.ifBlank { "Пусто" }, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { tailnetLogs = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ClickRow(title, sub, onClick = { onChange(!value) }) { Switch(value, onChange) }
}
