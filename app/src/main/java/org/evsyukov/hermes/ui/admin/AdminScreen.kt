package org.evsyukov.hermes.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.ui.theme.ChatTheme

enum class AdminSection(val title: String, val hint: String, val icon: ImageVector) {
    OVERVIEW("Состояние", "Gateway, платформы, ресурсы, обновления", Icons.Outlined.MonitorHeart),
    MODEL("Модель", "Основная модель новых чатов", Icons.Outlined.Psychology),
    CONFIG("Настройки", "config.yaml по схеме и целиком", Icons.Outlined.Tune),
    SECRETS("Секреты", "Ключи и переменные .env", Icons.Outlined.Key),
    CRON("Расписание", "Задачи cron: запуск, пауза, история", Icons.Outlined.Schedule),
    TOOLS("Навыки и инструменты", "Skills, toolsets, MCP-серверы", Icons.Outlined.Extension),
    PLATFORMS("Каналы", "Telegram, Matrix и другие платформы", Icons.Outlined.Forum),
    LOGS("Журналы", "agent, errors, gateway, mcp", Icons.AutoMirrored.Outlined.Article),
    USAGE("Расход", "Токены и стоимость по дням и моделям", Icons.AutoMirrored.Outlined.ShowChart),
}

@Composable
fun AdminScreen(api: AdminApi, section: AdminSection?, onOpen: (AdminSection) -> Unit, onBack: () -> Unit) {
    when (section) {
        null -> AdminHub(api, onOpen, onBack)
        AdminSection.OVERVIEW -> OverviewScreen(api, onBack)
        AdminSection.MODEL -> ModelScreen(api, onBack)
        AdminSection.CONFIG -> ConfigScreen(api, onBack)
        AdminSection.SECRETS -> SecretsScreen(api, onBack)
        AdminSection.CRON -> CronScreen(api, onBack)
        AdminSection.TOOLS -> ToolsScreen(api, onBack)
        AdminSection.PLATFORMS -> PlatformsScreen(api, onBack)
        AdminSection.LOGS -> LogsScreen(api, onBack)
        AdminSection.USAGE -> UsageScreen(api, onBack)
    }
}

@Composable
private fun AdminHub(api: AdminApi, onOpen: (AdminSection) -> Unit, onBack: () -> Unit) {
    val status = rememberLoadable { api.status() }
    AdminScaffold("Управление сервером", onBack, status.loading, status.reload) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            status.value?.let { StatusSummary(it) }
            ErrorText(status.error)
            AdminSection.entries.forEach { section ->
                ClickRow(section.title, section.hint, onClick = { onOpen(section) }) {}
            }
        }
    }
}

@Composable
private fun StatusSummary(status: JsonObject) {
    val colors = ChatTheme.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.string("overall")?.let { it == "ok" })
            Spacer(Modifier.width(8.dp))
            Text("Hermes ${status.string("version").orEmpty()}", color = colors.text, fontSize = 17.sp)
        }
        Text(
            "Gateway: ${status.string("gateway_state") ?: "?"} · активных сессий: ${status.long("active_sessions") ?: 0}",
            color = colors.secondaryText, fontSize = 14.sp,
        )
    }
}

@Composable
private fun OverviewScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val status = rememberLoadable { api.status() }
    val stats = rememberLoadable { api.systemStats() }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<JsonObject?>(null) }

    AdminScaffold("Состояние", onBack, status.loading || stats.loading, { status.reload(); stats.reload() }, snackbar) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ErrorText(status.error)
            status.value?.let { s ->
                SectionTitle("Hermes")
                Card {
                    KeyValue("Версия", "${s.string("version").orEmpty()} (${s.string("release_date").orEmpty()})")
                    KeyValue("Gateway", s.string("gateway_state") ?: "?", if (s.bool("gateway_running") == true) colors.success else colors.danger)
                    KeyValue("Активные агенты", (s.long("active_agents") ?: 0).toString())
                    KeyValue("Активные сессии", (s.long("active_sessions") ?: 0).toString())
                    KeyValue("Профили", (s["profiles"] as? kotlinx.serialization.json.JsonArray)?.joinToString { it.toString().trim('"') }.orEmpty())
                    (s["memory"] as? JsonObject)?.let { m ->
                        KeyValue("Память gateway", "${m.long("gateway_rss_mb") ?: 0} МБ · свободно ${m.long("system_available_mb") ?: 0} МБ")
                    }
                    (s["disk"] as? JsonObject)?.let { d -> KeyValue("Диск", "занято ${d.double("used_percent") ?: 0.0}% · свободно ${(d.long("free_mb") ?: 0) / 1024} ГБ") }
                }
                SectionTitle("Платформы")
                Card {
                    (s["gateway_platforms"] as? JsonObject)?.forEach { (name, value) ->
                        val p = value as? JsonObject ?: return@forEach
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(p.string("state") == "connected")
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = colors.text, modifier = Modifier.weight(1f))
                            Text(p.string("state").orEmpty(), color = colors.secondaryText, fontSize = 13.sp)
                        }
                        p.string("error_message")?.let { Text(it, color = colors.danger, fontSize = 12.sp) }
                    }
                }
                SectionTitle("Компоненты")
                Card {
                    (s["components"] as? JsonObject)?.forEach { (name, value) ->
                        val c = value as? JsonObject ?: return@forEach
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(c.string("status") == "ok")
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = colors.text, modifier = Modifier.weight(1f))
                            Text(c.string("status").orEmpty(), color = colors.secondaryText, fontSize = 13.sp)
                        }
                    }
                }
            }
            stats.value?.let { st ->
                SectionTitle("Система")
                Card {
                    KeyValue("Хост", "${st.string("hostname").orEmpty()} · ${st.string("system").orEmpty()} ${st.string("arch").orEmpty()}")
                    KeyValue("CPU", "${st.double("cpu_percent") ?: 0.0}% из ${st.long("cpu_count") ?: 0} ядер")
                    (st["load_avg"] as? kotlinx.serialization.json.JsonArray)?.let { KeyValue("Нагрузка", it.joinToString(" ") { v -> v.toString().take(5) }) }
                    st.long("uptime_seconds")?.let { KeyValue("Работает", "${it / 86400} д ${it % 86400 / 3600} ч") }
                }
            }

            SectionTitle("Gateway")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    confirm = "Перезапуск прервёт идущие ответы во всех каналах. Перезапустить gateway?" to {
                        scope.attempt(snackbar) { action = api.gateway("restart").string("name") ?: "gateway-restart" }
                    }
                }) { Text("Перезапустить") }
                OutlinedButton(onClick = { scope.attempt(snackbar) { action = api.gateway("start").string("name") ?: "gateway-start" } }) { Text("Запустить") }
                OutlinedButton(onClick = {
                    confirm = "Остановить gateway? Telegram, Matrix и cron перестанут работать до запуска." to {
                        scope.attempt(snackbar) { action = api.gateway("stop", interrupt = true).string("name") ?: "gateway-stop" }
                    }
                }) { Text("Остановить", color = colors.danger) }
            }

            SectionTitle("Обновление")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.attempt(snackbar) { update = api.updateCheck(force = true) } }) { Text("Проверить обновления") }
                update?.let { u ->
                    Text(
                        u.string("message") ?: if (u.bool("update_available") == true) "Есть обновление" else "Обновлений нет",
                        color = colors.secondaryText, fontSize = 14.sp,
                    )
                    if (u.bool("update_available") == true && u.bool("can_apply") == true) {
                        Button(
                            onClick = {
                                confirm = "Hermes обновится и перезапустится. Продолжить?" to {
                                    scope.attempt(snackbar) { action = api.update().string("name") ?: "hermes-update" }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
                        ) { Text("Обновить") }
                    } else if (u.bool("update_available") == true) {
                        Text("Обновить отсюда нельзя: ${u.string("update_command") ?: "установка управляется снаружи"}", color = colors.faintText, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }
    confirm?.let { (text, run) -> ConfirmDialog("Подтвердите", text, "Да", run) { confirm = null } }
    action?.let { name -> ActionLog(api, name) { action = null; status.reload() } }
}
