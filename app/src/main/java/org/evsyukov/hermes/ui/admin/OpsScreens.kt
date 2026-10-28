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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val jobs = rememberLoadable { api.cronJobs() }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    AdminScaffold(
        "Расписание", onBack, jobs.loading, jobs.reload, snackbar,
        actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "Новая задача") } },
    ) {
        ErrorText(jobs.error)
        if (jobs.value?.isEmpty() == true) Text("Задач нет", color = colors.faintText, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(jobs.value.orEmpty(), key = { it.string("id").orEmpty() }) { job ->
                val id = job.string("id").orEmpty()
                val paused = job.string("state") == "paused" || job.bool("enabled") == false
                ClickRow(
                    job.string("name") ?: id,
                    "${job.string("schedule_display").orEmpty()} · следующий ${time(job.string("next_run_at"))}" +
                        (job.string("last_status")?.let { " · последний: $it" } ?: ""),
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
        val runs = rememberLoadable(id) { api.cronRuns(id) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(job.string("name") ?: id) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue("Расписание", job.string("schedule_display").orEmpty())
                    KeyValue("Доставка", job.string("deliver").orEmpty())
                    KeyValue("Последний запуск", time(job.string("last_run_at")))
                    job.string("last_error")?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                    job.string("prompt")?.takeIf { it.isNotBlank() }?.let {
                        Text("Задание", color = colors.faintText, fontSize = 12.sp)
                        Text(it, color = colors.text, fontSize = 14.sp)
                    }
                    job.string("script")?.takeIf { it.isNotBlank() }?.let { KeyValue("Скрипт", it) }
                    Text("Запуски", color = colors.faintText, fontSize = 12.sp)
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
                        confirm = "Запустить «${job.string("name") ?: id}» сейчас? Задача потратит токены и может отправить сообщение." to {
                            scope.attempt(snackbar, "Запущено") { api.cronCommand(id, "trigger"); jobs.reload() }
                        }
                    }) { Text("Запустить") }
                    TextButton(onClick = {
                        confirm = "Удалить задачу «${job.string("name") ?: id}»?" to {
                            scope.attempt(snackbar, "Удалено") { api.deleteCron(id); open = null; jobs.reload() }
                        }
                    }) { Text("Удалить", color = colors.danger) }
                }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
        )
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var schedule by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var deliver by remember { mutableStateOf("local") }
        val targets = rememberLoadable { api.cronTargets() }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Новая задача") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(schedule, { schedule = it }, label = { Text("Расписание") }, placeholder = { Text("0 9 * * *, every 2h, 30m") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("Что сделать") }, minLines = 3)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(deliver == "local", { deliver = "local" }, label = { Text("Только сохранить") })
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
                    scope.attempt(snackbar, "Задача создана") { api.createCron(schedule.trim(), prompt.trim(), name.trim(), deliver); jobs.reload() }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } },
        )
    }
    confirm?.let { (text, run) -> ConfirmDialog("Подтвердите", text, "Да", run) { confirm = null } }
}

@Composable
fun ToolsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val skills = rememberLoadable { api.skills() }
    val toolsets = rememberLoadable { api.toolsets() }
    val mcp = rememberLoadable { api.mcpServers() }
    var skillText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember { mutableStateOf("") }

    AdminScaffold("Навыки и инструменты", onBack, skills.loading || toolsets.loading || mcp.loading, { skills.reload(); toolsets.reload(); mcp.reload() }, snackbar) {
        PrimaryTabRow(tab, containerColor = colors.background) {
            listOf("Навыки", "Инструменты", "MCP").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (tab == 0) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
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
                        val sub = listOfNotNull(set.string("description"), "$tools инстр.", if (set.bool("available") == false) "недоступен" else null, if (set.bool("configured") == false) "не настроен" else null)
                        ClickRow(set.string("label") ?: name, sub.joinToString(" · "), onClick = {}) {
                            Switch(set.bool("enabled") == true, { on -> scope.attempt(snackbar) { api.toggleToolset(name, on); toolsets.reload() } })
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
            else -> {
                ErrorText(mcp.error)
                Text("Изменения MCP вступают в силу со следующей сессии или после перезапуска gateway.", color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(mcp.value.orEmpty(), key = { it.string("name").orEmpty() }) { server ->
                        val name = server.string("name").orEmpty()
                        val tools = (server["tools"] as? JsonArray)?.size
                        ClickRow(name, listOfNotNull(server.string("transport"), server.string("url") ?: server.string("command"), tools?.let { "$it инстр." }).joinToString(" · "), onClick = {
                            scope.attempt(snackbar) {
                                val result = api.testMcp(name)
                                val count = (result["tools"] as? JsonArray)?.size ?: 0
                                snackbar.showSnackbar(if (result.bool("ok") == true) "$name: работает, $count инструментов" else "$name: ${result.string("error") ?: "не отвечает"}")
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
            confirmButton = { TextButton(onClick = { skillText = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
fun PlatformsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val platforms = rememberLoadable { api.platforms() }
    AdminScaffold("Каналы", onBack, platforms.loading, platforms.reload, snackbar) {
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
                            listOfNotNull(state, p.string("home_channel")?.let { "дом: $it" }).joinToString(" · "),
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
                        }) { Text("Проверить") }
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
    AdminScaffold("Журналы", onBack, onRefresh = { tick++ }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LOG_FILES.forEach { f -> FilterChip(file == f, { file = f }, label = { Text(f) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LOG_LEVELS.forEach { l -> FilterChip(level == l, { level = l }, label = { Text(l) }) }
            FilterChip(follow, { follow = !follow }, label = { Text("Следить") })
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text("Фильтр по тексту") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
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
    val usage = rememberLoadable(days) { api.usage(days) }
    AdminScaffold("Расход", onBack, usage.loading, usage.reload) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "День", 7 to "Неделя", 30 to "Месяц", 90 to "3 месяца").forEach { (d, label) -> FilterChip(days == d, { days = d }, label = { Text(label) }) }
        }
        ErrorText(usage.error)
        val data = usage.value ?: return@AdminScaffold
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            (data["totals"] as? JsonObject)?.let { t ->
                Card {
                    val cost = (t.double("total_actual_cost") ?: 0.0).takeIf { it > 0 } ?: t.double("total_estimated_cost") ?: 0.0
                    Text("$${"%.2f".format(cost)}", color = colors.text, fontSize = 30.sp)
                    KeyValue("Сессии", (t.long("total_sessions") ?: 0).toString())
                    KeyValue("Вызовы API", (t.long("total_api_calls") ?: 0).toString())
                    KeyValue("Токены на входе", tokens(t.long("total_input")))
                    KeyValue("Токены на выходе", tokens(t.long("total_output")))
                    KeyValue("Из кэша", tokens(t.long("total_cache_read")))
                    KeyValue("Рассуждения", tokens(t.long("total_reasoning")))
                }
            }
            val daily = (data["daily"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (daily.isNotEmpty()) {
                SectionTitle("По дням, $")
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
                SectionTitle("По моделям")
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

