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
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.ui.theme.ChatTheme

enum class AdminSection(@StringRes val title: Int, @StringRes val hint: Int, val icon: ImageVector) {
    OVERVIEW(R.string.admin_overview, R.string.admin_overview_hint, Icons.Outlined.MonitorHeart),
    MODEL(R.string.admin_model, R.string.admin_model_hint, Icons.Outlined.Psychology),
    CONFIG(R.string.admin_config, R.string.admin_config_hint, Icons.Outlined.Tune),
    SECRETS(R.string.admin_secrets, R.string.admin_secrets_hint, Icons.Outlined.Key),
    CRON(R.string.admin_cron, R.string.admin_cron_hint, Icons.Outlined.Schedule),
    TOOLS(R.string.admin_tools, R.string.admin_tools_hint, Icons.Outlined.Extension),
    PLATFORMS(R.string.admin_platforms, R.string.admin_platforms_hint, Icons.Outlined.Forum),
    LOGS(R.string.admin_logs, R.string.admin_logs_hint, Icons.AutoMirrored.Outlined.Article),
    USAGE(R.string.admin_usage, R.string.admin_usage_hint, Icons.AutoMirrored.Outlined.ShowChart),
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
    val status = rememberCached(api) { it.status() }
    AdminScaffold(stringResource(R.string.sidebar_server), onBack, status.loading, status.reload) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            status.value?.let { StatusSummary(it) }
            ErrorText(status.error)
            AdminSection.entries.forEach { section ->
                ClickRow(stringResource(section.title), stringResource(section.hint), onClick = { onOpen(section) }) {}
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
            stringResource(R.string.admin_summary, status.string("gateway_state") ?: "?", status.long("active_sessions") ?: 0),
            color = colors.secondaryText, fontSize = 14.sp,
        )
    }
}

@Composable
private fun OverviewScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val status = rememberCached(api) { it.status() }
    val stats = rememberCached(api) { it.systemStats() }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<JsonObject?>(null) }
    val restartConfirm = stringResource(R.string.admin_restart_confirm)
    val stopConfirm = stringResource(R.string.admin_stop_confirm)
    val updateConfirm = stringResource(R.string.admin_update_confirm)

    AdminScaffold(stringResource(R.string.admin_overview), onBack, status.loading || stats.loading, { status.reload(); stats.reload() }, snackbar) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ErrorText(status.error)
            status.value?.let { s ->
                SectionTitle("Hermes")
                Card {
                    KeyValue(stringResource(R.string.admin_overview_version), "${s.string("version").orEmpty()} (${s.string("release_date").orEmpty()})")
                    KeyValue("Gateway", s.string("gateway_state") ?: "?", if (s.bool("gateway_running") == true) colors.success else colors.danger)
                    KeyValue(stringResource(R.string.admin_overview_agents), (s.long("active_agents") ?: 0).toString())
                    KeyValue(stringResource(R.string.admin_overview_sessions), (s.long("active_sessions") ?: 0).toString())
                    KeyValue(stringResource(R.string.admin_overview_profiles), (s["profiles"] as? kotlinx.serialization.json.JsonArray)?.joinToString { it.toString().trim('"') }.orEmpty())
                    (s["memory"] as? JsonObject)?.let { m ->
                        KeyValue(stringResource(R.string.admin_overview_memory), stringResource(R.string.admin_overview_memory_value, m.long("gateway_rss_mb") ?: 0, m.long("system_available_mb") ?: 0))
                    }
                    (s["disk"] as? JsonObject)?.let { d -> KeyValue(stringResource(R.string.admin_overview_disk), stringResource(R.string.admin_overview_disk_value, d.double("used_percent") ?: 0.0, (d.long("free_mb") ?: 0) / 1024)) }
                }
                SectionTitle(stringResource(R.string.admin_overview_platforms))
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
                SectionTitle(stringResource(R.string.admin_overview_components))
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
                SectionTitle(stringResource(R.string.admin_overview_system))
                Card {
                    KeyValue(stringResource(R.string.admin_overview_host), "${st.string("hostname").orEmpty()} · ${st.string("system").orEmpty()} ${st.string("arch").orEmpty()}")
                    val cores = st.long("cpu_count") ?: 0
                    KeyValue("CPU", pluralStringResource(R.plurals.admin_overview_cpu_value, cores.toInt(), st.double("cpu_percent") ?: 0.0, cores))
                    (st["load_avg"] as? kotlinx.serialization.json.JsonArray)?.let { KeyValue(stringResource(R.string.admin_overview_load), it.joinToString(" ") { v -> v.toString().take(5) }) }
                    st.long("uptime_seconds")?.let { KeyValue(stringResource(R.string.admin_overview_uptime), stringResource(R.string.admin_overview_uptime_value, it / 86400, it % 86400 / 3600)) }
                }
            }

            SectionTitle("Gateway")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    confirm = restartConfirm to {
                        scope.attempt(snackbar) { action = api.gateway("restart").string("name") ?: "gateway-restart" }
                    }
                }) { Text(stringResource(R.string.admin_restart)) }
                OutlinedButton(onClick = { scope.attempt(snackbar) { action = api.gateway("start").string("name") ?: "gateway-start" } }) { Text(stringResource(R.string.admin_start)) }
                OutlinedButton(onClick = {
                    confirm = stopConfirm to {
                        scope.attempt(snackbar) { action = api.gateway("stop", interrupt = true).string("name") ?: "gateway-stop" }
                    }
                }) { Text(stringResource(R.string.admin_stop), color = colors.danger) }
            }

            SectionTitle(stringResource(R.string.admin_update_title))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.attempt(snackbar) { update = api.updateCheck(force = true) } }) { Text(stringResource(R.string.admin_update_check)) }
                update?.let { u ->
                    Text(
                        u.string("message") ?: stringResource(if (u.bool("update_available") == true) R.string.admin_update_available else R.string.admin_update_none),
                        color = colors.secondaryText, fontSize = 14.sp,
                    )
                    if (u.bool("update_available") == true && u.bool("can_apply") == true) {
                        Button(
                            onClick = {
                                confirm = updateConfirm to {
                                    scope.attempt(snackbar) { action = api.update().string("name") ?: "hermes-update" }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
                        ) { Text(stringResource(R.string.admin_update_apply)) }
                    } else if (u.bool("update_available") == true) {
                        Text(stringResource(R.string.admin_update_manual, u.string("update_command") ?: stringResource(R.string.admin_update_external)), color = colors.faintText, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }
    confirm?.let { (text, run) -> ConfirmDialog(stringResource(R.string.common_confirm_title), text, stringResource(R.string.common_yes), run) { confirm = null } }
    action?.let { name -> ActionLog(api, name) { action = null; status.reload() } }
}
