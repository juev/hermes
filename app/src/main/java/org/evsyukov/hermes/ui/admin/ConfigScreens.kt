package org.evsyukov.hermes.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.ConfigField
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.ui.theme.ChatTheme

@Composable
fun ModelScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val info = rememberLoadable { api.modelInfo() }
    val options = rememberLoadable { api.modelOptions() }
    var confirm by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    fun pick(provider: String, model: String, confirmed: Boolean = false) = scope.attempt(snackbar) {
        val result = api.setModel(provider, model, confirmed)
        if (result.bool("confirm_required") == true) {
            confirm = Triple(provider, model, result.string("confirm_message") ?: "Модель дорогая.")
        } else {
            snackbar.showSnackbar(result.string("notice") ?: "Новые чаты пойдут на $model")
            info.reload()
            options.reload()
        }
    }

    AdminScaffold("Модель", onBack, info.loading || options.loading, { info.reload(); options.reload() }, snackbar) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                info.value?.let { m ->
                    Card {
                        KeyValue("Сейчас", m.string("model").orEmpty())
                        KeyValue("Провайдер", m.string("provider").orEmpty())
                        m.string("effective_context_length")?.let { KeyValue("Контекст", it) }
                    }
                }
                Text(
                    "Выбор меняет config.yaml и действует на новые чаты. Модель одного чата меняется в самом чате, нажатием на заголовок.",
                    color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
                ErrorText(info.error ?: options.error)
            }
            options.value?.providers?.forEach { provider ->
                item(key = "p:${provider.slug}") {
                    SectionTitle(provider.name + if (provider.current) " · текущий" else "")
                    provider.warning?.let { Text(it, color = colors.warning, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(provider.models, key = { "${provider.slug}/$it" }) { model ->
                    val current = model == info.value?.string("model") && provider.slug == info.value.string("provider")
                    ClickRow(model, onClick = { pick(provider.slug, model) }) {
                        if (current) Icon(Icons.Outlined.Check, null, tint = colors.text)
                    }
                }
            }
        }
    }
    confirm?.let { (provider, model, message) ->
        ConfirmDialog("Дорогая модель", message, "Переключить", { pick(provider, model, confirmed = true) }) { confirm = null }
    }
}

@Composable
fun ConfigScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val schema = rememberLoadable { api.schema() }
    val config = rememberLoadable { api.config() }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ConfigField?>(null) }
    var raw by remember { mutableStateOf(false) }

    AdminScaffold(
        "Настройки", onBack, schema.loading || config.loading, { schema.reload(); config.reload() }, snackbar,
        actions = { IconButton(onClick = { raw = true }) { Icon(Icons.Outlined.Code, "config.yaml целиком") } },
    ) {
        val fields = schema.value?.fields.orEmpty()
        val categories = remember(schema.value) {
            val order = schema.value?.categoryOrder.orEmpty()
            fields.map { it.category }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Поиск по ключам и описаниям") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category == null, { category = null }, label = { Text("Все") })
                categories.forEach { c -> FilterChip(category == c, { category = c }, label = { Text(c) }) }
            }
            ErrorText(schema.error ?: config.error)
            val shown = fields.filter { f ->
                (category == null || f.category == category) &&
                    (query.isBlank() || f.path.contains(query, true) || f.description.contains(query, true))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { field ->
                    val value = config.value?.let { AdminApi.valueAt(it, field.path) } ?: JsonNull
                    ClickRow(field.path, field.description.takeIf { it != field.path }, onClick = { editing = field }) {
                        Text(display(value), color = colors.secondaryText, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp))
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
    }

    editing?.let { field ->
        val current = config.value?.let { AdminApi.valueAt(it, field.path) } ?: JsonNull
        FieldEditor(field, current, onDismiss = { editing = null }) { value ->
            editing = null
            scope.attempt(snackbar, "Сохранено: ${field.path}") {
                api.setConfig(field.path, value)
                config.reload()
            }
        }
    }
    if (raw) RawConfigDialog(api, onDismiss = { raw = false }) { config.reload() }
}

private fun display(value: JsonElement): String = when (value) {
    is JsonNull -> "—"
    is JsonPrimitive -> value.content.take(40)
    else -> value.toString().take(40)
}

@Composable
private fun FieldEditor(field: ConfigField, current: JsonElement, onDismiss: () -> Unit, onSave: (JsonElement) -> Unit) {
    val colors = ChatTheme.colors
    val structured = field.type == "list" || field.type == "object" || current is JsonObject || current is kotlinx.serialization.json.JsonArray
    var text by remember {
        mutableStateOf(
            when {
                current is JsonNull -> ""
                current is JsonPrimitive -> current.content
                else -> HermesJson.encodeToString(JsonElement.serializer(), current)
            },
        )
    }
    var flag by remember { mutableStateOf((current as? JsonPrimitive)?.content == "true") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.path, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (field.description != field.path) Text(field.description, color = colors.secondaryText, fontSize = 14.sp)
                when {
                    field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (flag) "Включено" else "Выключено", modifier = Modifier.weight(1f))
                        Switch(flag, { flag = it })
                    }
                    field.options.isNotEmpty() -> {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                text, {}, readOnly = true,
                                trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Outlined.ArrowDropDown, null) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(open, { open = false }) {
                                field.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { text = option; open = false }) }
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        text, { text = it; error = null },
                        textStyle = if (structured) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else TextStyle.Default,
                        minLines = if (structured) 4 else 1,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    )
                }
                if (structured) Text("Значение в JSON", color = colors.faintText, fontSize = 12.sp)
                error?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value: JsonElement? = when {
                    field.type == "boolean" -> JsonPrimitive(flag)
                    field.type == "number" -> text.trim().toLongOrNull()?.let(::JsonPrimitive) ?: text.trim().toDoubleOrNull()?.let(::JsonPrimitive)
                    structured -> runCatching { HermesJson.parseToJsonElement(text) }.getOrNull()
                    else -> JsonPrimitive(text)
                }
                if (value == null) error = "Значение не разобрано" else onSave(value)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun RawConfigDialog(api: AdminApi, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = ChatTheme.colors
    val scope = rememberCoroutineScope()
    val loaded = rememberLoadable { api.rawConfig() }
    var text by remember(loaded.value) { mutableStateOf(loaded.value.orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("config.yaml") },
        text = {
            Column {
                Text("Файл заменяется целиком: удалённые строки исчезнут с сервера.", color = colors.warning, fontSize = 13.sp)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    text, { text = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 520.dp),
                )
                (error ?: loaded.error)?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(enabled = loaded.value != null && text != loaded.value, onClick = { confirm = true }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    if (confirm) {
        ConfirmDialog("Заменить config.yaml?", "Сервер перепишет файл этим текстом.", "Заменить", {
            scope.launchCatching({ error = it }) {
                api.saveRawConfig(text)
                onSaved()
                onDismiss()
            }
        }) { confirm = false }
    }
}

@Composable
fun SecretsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val env = rememberLoadable { api.env() }
    var query by remember { mutableStateOf("") }
    var onlySet by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var revealing by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<Pair<String, String>?>(null) }

    AdminScaffold("Секреты", onBack, env.loading, env.reload, snackbar) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(onlySet, { onlySet = true }, label = { Text("Заданные") })
                FilterChip(!onlySet, { onlySet = false }, label = { Text("Все") })
                FilterChip(false, { editing = "" }, label = { Text("+ Добавить") })
            }
            ErrorText(env.error)
            val vars = env.value.orEmpty().entries
                .filter { (key, v) -> (!onlySet || (v as? JsonObject)?.bool("is_set") == true) && (query.isBlank() || key.contains(query, true)) }
                .sortedBy { it.key }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vars, key = { it.key }) { (key, value) ->
                    val v = value as? JsonObject
                    val set = v?.bool("is_set") == true
                    ClickRow(key, v?.string("description") ?: v?.string("category"), onClick = { editing = key }) {
                        if (set) {
                            Text(v.string("redacted_value")?.removePrefix("«redacted:")?.removeSuffix("»") ?: "•••", color = colors.faintText, fontSize = 12.sp)
                            IconButton(onClick = { revealing = key }) { Icon(Icons.Outlined.Visibility, "Показать", tint = colors.secondaryText) }
                            IconButton(onClick = { deleting = key }) { Icon(Icons.Outlined.Delete, "Удалить", tint = colors.danger) }
                        } else {
                            Text("не задан", color = colors.faintText, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
    }
    editing?.let { key ->
        var name by remember(key) { mutableStateOf(key) }
        var value by remember(key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (key.isEmpty()) "Новая переменная" else key) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (key.isEmpty()) OutlinedTextField(name, { name = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, label = { Text("Имя") }, singleLine = true)
                    OutlinedTextField(value, { value = it }, label = { Text("Новое значение") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text("Значение уходит в .env сервера; прежнее заменится.", color = colors.faintText, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && value.isNotEmpty(), onClick = {
                    editing = null
                    scope.attempt(snackbar, "$name сохранён") { api.setEnv(name, value); env.reload() }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
    deleting?.let { key ->
        ConfirmDialog("Удалить $key?", "Ключ уйдёт из .env, связанные записи пула ключей тоже.", "Удалить", {
            scope.attempt(snackbar, "$key удалён") { api.deleteEnv(key); env.reload() }
        }) { deleting = null }
    }
    revealing?.let { key ->
        ConfirmDialog("Показать $key?", "Значение придёт на телефон открытым текстом. Сервер разрешает 5 показов за 30 секунд.", "Показать", {
            scope.attempt(snackbar) { revealed = key to api.revealEnv(key) }
        }) { revealing = null }
    }
    revealed?.let { (key, value) ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(key) },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { revealed = null }) { Text("Скрыть") } },
        )
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(onError: (String) -> Unit, block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (e: Exception) {
        onError(e.message ?: "Ошибка")
    }
}

