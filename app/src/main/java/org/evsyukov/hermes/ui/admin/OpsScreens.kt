package org.evsyukov.hermes.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.LogQuery
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.double
import org.evsyukov.hermes.data.remote.long
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.ui.common.MarkdownText
import org.evsyukov.hermes.ui.theme.ChatTheme

/** Cron timestamps come as ISO strings or unix seconds, depending on the field. */
private fun time(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    val millis = value.toDoubleOrNull()?.let { (it * 1000).toLong() }
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: return value
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}

@Composable
fun CronScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val jobs = rememberCached(api) { it.cronJobs() }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val resources = LocalResources.current

    AdminScaffold(
        stringResource(R.string.admin_cron), onBack, jobs.loading, jobs.reload, snackbar,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.admin_cron_new)) } },
    ) {
        ErrorText(jobs.error)
        if (jobs.value?.isEmpty() == true) Text(stringResource(R.string.admin_cron_none), color = colors.faintText, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(jobs.value.orEmpty(), key = { it.string("id").orEmpty() }) { job ->
                val id = job.string("id").orEmpty()
                val paused = job.string("state") == "paused" || job.bool("enabled") == false
                ClickRow(
                    job.string("name") ?: id,
                    stringResource(R.string.admin_cron_next, job.string("schedule_display").orEmpty(), time(job.string("next_run_at"))) +
                        (job.string("last_status")?.let { stringResource(R.string.admin_cron_last_status, it) } ?: ""),
                    onClick = { open = job },
                ) {
                    Switch(!paused, { on ->
                        scope.attempt(snackbar) { api.cronCommand(id, if (on) "resume" else "pause"); jobs.reload() }
                    })
                }
                HorizontalDivider(color = colors.divider)
            }
        }
    }

    open?.let { job ->
        val id = job.string("id").orEmpty()
        val runs = rememberCached(api, id) { it.cronRuns(id) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(job.string("name") ?: id) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue(stringResource(R.string.admin_cron), job.string("schedule_display").orEmpty())
                    KeyValue(stringResource(R.string.admin_cron_deliver), job.string("deliver").orEmpty())
                    KeyValue(stringResource(R.string.admin_cron_last_run), time(job.string("last_run_at")))
                    job.string("last_error")?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                    job.string("prompt")?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.admin_cron_task), color = colors.faintText, fontSize = 12.sp)
                        Text(it, color = colors.text, fontSize = 14.sp)
                    }
                    job.string("script")?.takeIf { it.isNotBlank() }?.let { KeyValue(stringResource(R.string.admin_cron_script), it) }
                    Text(stringResource(R.string.admin_cron_runs), color = colors.faintText, fontSize = 12.sp)
                    runs.value?.forEach { run ->
                        Text(
                            "${time(run.string("started_at"))} · ${run.string("preview") ?: run.string("id").orEmpty()}",
                            color = colors.secondaryText, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ErrorText(runs.error)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        confirm = resources.getString(R.string.admin_cron_run_confirm, job.string("name") ?: id) to {
                            scope.attempt(snackbar, resources.getString(R.string.admin_cron_started)) { api.cronCommand(id, "trigger"); jobs.reload() }
                        }
                    }) { Text(stringResource(R.string.admin_start)) }
                    TextButton(onClick = {
                        confirm = resources.getString(R.string.admin_cron_delete_confirm, job.string("name") ?: id) to {
                            scope.attempt(snackbar, resources.getString(R.string.admin_cron_deleted)) { api.deleteCron(id); open = null; jobs.reload() }
                        }
                    }) { Text(stringResource(R.string.common_delete), color = colors.danger) }
                }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var schedule by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var deliver by remember { mutableStateOf("local") }
        val targets = rememberCached(api) { it.cronTargets() }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.admin_cron_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.admin_cron_name)) }, singleLine = true)
                    OutlinedTextField(schedule, { schedule = it }, label = { Text(stringResource(R.string.admin_cron)) }, placeholder = { Text("0 9 * * *, every 2h, 30m") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.admin_cron_prompt)) }, minLines = 3)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(deliver == "local", { deliver = "local" }, label = { Text(stringResource(R.string.admin_cron_local)) })
                        targets.value?.forEach { t ->
                            val target = t.string("id").orEmpty()
                            FilterChip(deliver == target, { deliver = target }, label = { Text(t.string("name") ?: target) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = schedule.isNotBlank() && prompt.isNotBlank(), onClick = {
                    creating = false
                    scope.attempt(snackbar, resources.getString(R.string.admin_cron_created)) { api.createCron(schedule.trim(), prompt.trim(), name.trim(), deliver); jobs.reload() }
                }) { Text(stringResource(R.string.admin_cron_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    confirm?.let { (text, run) -> ConfirmDialog(stringResource(R.string.common_confirm_title), text, stringResource(R.string.common_yes), run) { confirm = null } }
}

@Composable
fun ToolsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val skills = rememberCached(api) { it.skills() }
    val toolsets = rememberCached(api) { it.toolsets() }
    val mcp = rememberCached(api) { it.mcpServers() }
    var skillText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember { mutableStateOf("") }
    val resources = LocalResources.current

    AdminScaffold(stringResource(R.string.admin_tools), onBack, skills.loading || toolsets.loading || mcp.loading, { skills.reload(); toolsets.reload(); mcp.reload() }, snackbar) {
        PrimaryTabRow(tab, containerColor = colors.background) {
            listOf(stringResource(R.string.admin_tab_skills), stringResource(R.string.admin_tab_tools), "MCP").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (tab == 0) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.common_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
        when (tab) {
            0 -> {
                ErrorText(skills.error)
                LazyColumn(Modifier.fillMaxSize()) {
                    val list = skills.value.orEmpty().filter { query.isBlank() || it.string("name").orEmpty().contains(query, true) || it.string("description").orEmpty().contains(query, true) }
                    items(list, key = { it.string("name").orEmpty() }) { skill ->
                        val name = skill.string("name").orEmpty()
                        ClickRow(name, skill.string("description"), onClick = {
                            scope.attempt(snackbar) { skillText = name to api.skillContent(name) }
                        }) {
                            Switch(skill.bool("enabled") != false, { on -> scope.attempt(snackbar) { api.toggleSkill(name, on); skills.reload() } })
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
            1 -> {
                ErrorText(toolsets.error)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(toolsets.value.orEmpty(), key = { it.string("name").orEmpty() }) { set ->
                        val name = set.string("name").orEmpty()
                        val tools = (set["tools"] as? JsonArray)?.size ?: 0
                        val sub = listOfNotNull(
                            set.string("description"),
                            stringResource(R.string.admin_tools_count, tools),
                            if (set.bool("available") == false) stringResource(R.string.admin_tools_unavailable) else null,
                            if (set.bool("configured") == false) stringResource(R.string.admin_tools_not_configured) else null,
                        )
                        ClickRow(set.string("label") ?: name, sub.joinToString(" · "), onClick = {}) {
                            Switch(set.bool("enabled") == true, { on -> scope.attempt(snackbar) { api.toggleToolset(name, on); toolsets.reload() } })
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
            else -> {
                ErrorText(mcp.error)
                Text(stringResource(R.string.admin_mcp_hint), color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(mcp.value.orEmpty(), key = { it.string("name").orEmpty() }) { server ->
                        val name = server.string("name").orEmpty()
                        val tools = (server["tools"] as? JsonArray)?.size
                        ClickRow(name, listOfNotNull(server.string("transport"), server.string("url") ?: server.string("command"), tools?.let { stringResource(R.string.admin_tools_count, it) }).joinToString(" · "), onClick = {
                            scope.attempt(snackbar) {
                                val result = api.testMcp(name)
                                val count = (result["tools"] as? JsonArray)?.size ?: 0
                                snackbar.showSnackbar(
                                    if (result.bool("ok") == true) {
                                        resources.getQuantityString(R.plurals.admin_mcp_works, count, name, count)
                                    } else {
                                        "$name: ${result.string("error") ?: resources.getString(R.string.admin_mcp_no_answer)}"
                                    },
                                )
                            }
                        }) {
                            Switch(server.bool("enabled") != false, { on -> scope.attempt(snackbar) { api.toggleMcp(name, on); mcp.reload() } })
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
    }
    skillText?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { skillText = null },
            title = { Text(name) },
            text = { Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { MarkdownText(text) } },
            confirmButton = { TextButton(onClick = { skillText = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

@Composable
fun PlatformsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val platforms = rememberCached(api) { it.platforms() }
    AdminScaffold(stringResource(R.string.admin_platforms), onBack, platforms.loading, platforms.reload, snackbar) {
        ErrorText(platforms.error)
        LazyColumn(Modifier.fillMaxSize()) {
            val list = platforms.value.orEmpty().sortedByDescending { it.bool("enabled") == true }
            items(list, key = { it.string("id").orEmpty() }) { p ->
                val id = p.string("id").orEmpty()
                val state = p.string("state").orEmpty()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (p.bool("enabled") != true) null else state == "connected")
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.string("name") ?: id, color = colors.text)
                        Text(
                            listOfNotNull(state, p.string("home_channel")?.let { stringResource(R.string.admin_platforms_home, it) }).joinToString(" · "),
                            color = colors.faintText, fontSize = 13.sp,
                        )
                        p.string("error_message")?.let { Text(it, color = colors.danger, fontSize = 12.sp) }
                    }
                    if (p.bool("enabled") == true) {
                        TextButton(onClick = {
                            scope.attempt(snackbar) {
                                val r = api.testPlatform(id)
                                snackbar.showSnackbar("${p.string("name") ?: id}: ${r.string("message") ?: r.string("state") ?: "?"}")
                            }
                        }) { Text(stringResource(R.string.admin_platforms_test)) }
                    }
                }
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

private val LOG_FILES = listOf("agent", "errors", "gateway", "mcp", "update", "gui")
private val LOG_LEVELS = listOf("ALL", "DEBUG", "INFO", "WARNING", "ERROR")

@Composable
fun LogsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    var file by remember { mutableStateOf("agent") }
    var level by remember { mutableStateOf("ALL") }
    var search by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    // The dashboard has no log stream; following polls the tail every few seconds.
    LaunchedEffect(file, level, search, follow, tick) {
        while (true) {
            try {
                lines = api.logs(LogQuery(file, 500, level, search = search))
                error = null
                if (follow && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
            } catch (e: Exception) {
                error = e.message
            }
            if (!follow) break
            delay(4_000)
        }
    }
    AdminScaffold(stringResource(R.string.admin_logs), onBack, onRefresh = { tick++ }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LOG_FILES.forEach { f -> FilterChip(file == f, { file = f }, label = { Text(f) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LOG_LEVELS.forEach { l -> FilterChip(level == l, { level = l }, label = { Text(l) }) }
            FilterChip(follow, { follow = !follow }, label = { Text(stringResource(R.string.admin_logs_follow)) })
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.admin_logs_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        ErrorText(error)
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(10.dp)).background(colors.codeBackground), state = list) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = when {
                            " ERROR " in line || "Traceback" in line -> colors.danger
                            " WARNING " in line -> colors.warning
                            else -> colors.secondaryText
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun UsageScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    var days by remember { mutableIntStateOf(30) }
    val usage = rememberCached(api, days) { it.usage(days) }
    AdminScaffold(stringResource(R.string.admin_usage), onBack, usage.loading, usage.reload) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                1 to stringResource(R.string.admin_usage_day),
                7 to stringResource(R.string.admin_usage_week),
                30 to stringResource(R.string.admin_usage_month),
                90 to stringResource(R.string.admin_usage_quarter),
            ).forEach { (d, label) -> FilterChip(days == d, { days = d }, label = { Text(label) }) }
        }
        ErrorText(usage.error)
        val data = usage.value ?: return@AdminScaffold
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            (data["totals"] as? JsonObject)?.let { t ->
                Card {
                    val cost = (t.double("total_actual_cost") ?: 0.0).takeIf { it > 0 } ?: t.double("total_estimated_cost") ?: 0.0
                    Text("$${"%.2f".format(cost)}", color = colors.text, fontSize = 30.sp)
                    KeyValue(stringResource(R.string.admin_usage_sessions), (t.long("total_sessions") ?: 0).toString())
                    KeyValue(stringResource(R.string.admin_usage_calls), (t.long("total_api_calls") ?: 0).toString())
                    KeyValue(stringResource(R.string.admin_usage_input), tokens(t.long("total_input")))
                    KeyValue(stringResource(R.string.admin_usage_output), tokens(t.long("total_output")))
                    KeyValue(stringResource(R.string.admin_usage_cache), tokens(t.long("total_cache_read")))
                    KeyValue(stringResource(R.string.admin_usage_reasoning), tokens(t.long("total_reasoning")))
                }
            }
            val daily = (data["daily"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (daily.isNotEmpty()) {
                SectionTitle(stringResource(R.string.admin_usage_by_day))
                val max = daily.maxOf { cost(it) }.coerceAtLeast(0.0001)
                Row(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    daily.forEach { day ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight((cost(day) / max).toFloat().coerceAtLeast(0.01f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(colors.contrast),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(daily.first().string("day").orEmpty(), color = colors.faintText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(daily.last().string("day").orEmpty(), color = colors.faintText, fontSize = 11.sp)
                }
            }
            val models = (data["by_model"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (models.isNotEmpty()) {
                SectionTitle(stringResource(R.string.admin_usage_by_model))
                Card {
                    models.sortedByDescending { cost(it) }.forEach { m ->
                        KeyValue(m.string("model").orEmpty(), "$${"%.2f".format(cost(m))} · ${tokens((m.long("input_tokens") ?: 0) + (m.long("output_tokens") ?: 0))}")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun cost(row: JsonObject): Double = (row.double("actual_cost") ?: 0.0).takeIf { it > 0 } ?: row.double("estimated_cost") ?: 0.0

private fun tokens(n: Long?): String {
    val v = n ?: 0
    return when {
        v >= 1_000_000 -> "%.1f M".format(v / 1_000_000.0)
        v >= 1_000 -> "%.1f K".format(v / 1_000.0)
        else -> v.toString()
    }
}

